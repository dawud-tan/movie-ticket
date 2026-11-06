package com.protek.booking.payment.snap

import com.protek.booking.isJavaBlank
import com.protek.booking.payment.MidtransGateway
import com.protek.booking.payment.MidtransGateway.ChargeResult
import com.protek.booking.payment.MidtransGateway.TransactionStatus
import com.protek.booking.payment.MidtransJson
import com.protek.booking.payment.snap.SnapAccessTokenProvider.SnapException
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.UUID

/**
 * The BI-SNAP implementation of [MidtransGateway], sitting beside
 * `HttpMidtransGateway` rather than replacing it — the two APIs must run together through
 * the cutover drain, because a transaction created on one cannot be processed by the other.
 *
 * Proven against sandbox before being written: a probe script returned
 * response code 2004700 and a scannable QR, which is where the request shape below comes from rather
 * than from the docs alone.
 */
/*
 * Selected by `midtrans.api=snap`, with HttpMidtransGateway marked @Reserve so it wins
 * whenever this one is absent. That combination — rather than @Typed, which previously kept this
 * class unreachable — is what lets BI-SNAP actually create charges.
 *
 * Deliberately a BUILD-time property: it decides which bean exists, so flipping it needs a
 * rebuild. That is the right granularity for a cutover that requires a deploy anyway, and it
 * means the payment path cannot be changed by editing an env file on a live box.
 *
 * `midtrans.api` defaults to `snap` as of 2026-08-17, so this bean is what a plain build now
 * produces. The default previously read `legacy`, which pointed the wrong way for how this is
 * actually deployed: every build meant for BI-SNAP had to remember -Dmidtrans.api=snap, and
 * forgetting it succeeded and charged through the Core API instead. Set midtrans.api=legacy to
 * suppress this bean and fall back to HttpMidtransGateway.
 *
 * Before flipping it, drain pending charges: a transaction created on one API
 * cannot be processed by the other, so the switch belongs on a zero-pending window.
 */
@IfBuildProperty(name = "midtrans.api", stringValue = "snap")
@ApplicationScoped
class SnapMidtransGateway : MidtransGateway {

    @Inject
    lateinit var credentials: SnapCredentials

    @Inject
    lateinit var tokens: SnapAccessTokenProvider

    // Public, nullable fields: SnapMidtransGatewayTest builds a gateway by assigning these.
    @ConfigProperty(name = "midtrans.snap.base-url")
    @JvmField
    final var baseUrl: String? = null

    @ConfigProperty(name = "midtrans.merchant-id")
    @JvmField
    final var merchantId: String? = null

    @ConfigProperty(name = "midtrans.qris.acquirer", defaultValue = "gopay")
    @JvmField
    final var acquirer: String? = null

    @ConfigProperty(name = "midtrans.snap.http-timeout-seconds", defaultValue = "15")
    @JvmField
    final var timeoutSeconds: Int = 15

    private lateinit var httpClient: HttpClient

    @PostConstruct
    fun init() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .version(HttpClient.Version.HTTP_1_1)
            .build()
        if (credentials.isConfigured() && !isSet(merchantId)) {
            // The specific shape of "everything looks configured but charges fail": the six SNAP
            // credentials load, the startup banner says configured, and then every charge comes
            // back 4004701 because merchantId went out as the literal "__unset__". Said once, at
            // boot, naming the variable — because the alternative is finding it from a customer's
            // 500. PaymentGatewayStartupLog runs at startup and touches this bean, so this fires
            // there rather than on the first charge.
            LOG.errorf("BI-SNAP credentials loaded but midtrans.merchant-id (MIDTRANS_MERCHANT_ID) " +
                "is unset. BI-SNAP sends it as merchantId in every charge and Midtrans " +
                "rejects those with 4004701 Invalid Field Format. The legacy API never sent " +
                "it, so this only bites after switching. Charges fall back to pay-at-counter " +
                "until it is set in the environment.")
        }
    }

    /**
     * **Includes `midtrans.merchant-id`, which is not one of [SnapCredentials]' six.**
     *
     * It is shared with the legacy gateway and so predates the BI-SNAP credential set, but the
     * two APIs use it differently: the legacy charge body never carried it — it was only stored on
     * the payment row — whereas BI-SNAP sends it as `merchantId` in every charge. So a value
     * the legacy path tolerated being absent becomes mandatory the moment the switch is flipped,
     * and Midtrans rejects the request with `4004701 Invalid Field Format`.
     *
     * Checked here rather than added to `SnapCredentials`' all-or-none rule because it is not
     * part of the credential exchange and is required by the legacy path too; failing the whole
     * application's startup over it would be wrong. Reporting unconfigured means a booking falls
     * back to pay-at-counter instead of showing the customer a 500.
     */
    override fun isConfigured(): Boolean = credentials.isConfigured() && isSet(merchantId)

    override fun chargeQris(orderId: String, grossAmount: BigDecimal, expiryMinutes: Int): ChargeResult {
        val body = chargeBody(orderId, grossAmount, expiryMinutes)
        val response = post(CHARGE_PATH, body, orderId)

        val responseCode = MidtransJson.text(response, "responseCode")
        if (CHARGE_OK != responseCode) {
            // Same reasoning as the legacy gateway's status_code check: a charge that did not
            // produce a QR must fail here rather than leave a payment row pending forever with
            // nothing for the customer to scan.
            //
            // The whole response goes into the message, not just responseCode and
            // responseMessage. A 4004701 (Invalid Field Format) says which field it disliked
            // somewhere in that body, and projecting to two fields threw exactly that away — which
            // turned a one-line fix into a bisect against the probe script. The request body is
            // logged beside it because "which field" is only useful next to what was sent.
            LOG.errorf("BI-SNAP rejected charge for order %s.%n  response: %s%n  request:  %s",
                orderId, MidtransJson.render(response), body)
            throw SnapException("BI-SNAP refused the charge for order " + orderId +
                ": responseCode=" + responseCode + " " + MidtransJson.text(response, "responseMessage") +
                " — full response and the request body that produced it are logged at ERROR")
        }

        // qrUrl over qrContent deliberately, preserving the legacy gateway's choice: Midtrans hosts
        // the rendered image, so the browser can show it with an <img> and this codebase still
        // needs no QR-encoding library. qrContent is the raw EMV string and qrImage a
        // base64 PNG; both are present in the response if that trade is ever revisited.
        val qrContent = MidtransJson.text(response, "qrUrl") ?: MidtransJson.text(response, "qrContent")
            ?: throw SnapException("Charge response for order " + orderId +
                " carried neither qrUrl nor qrContent; nothing to show the customer")
        LOG.infof("BI-SNAP QRIS charge %s -> referenceNo %s", orderId, MidtransJson.text(response, "referenceNo"))
        // "pending" rather than a SNAP code: generate returns no transaction status of its own —
        // the QR exists and nobody has paid it yet — and PaymentService reads this in the legacy
        // vocabulary it already speaks.
        return ChargeResult(MidtransJson.text(response, "referenceNo"), qrContent, "pending")
    }

    /**
     * Built as one string and then both hashed and sent, never serialised twice: `minify` is
     * defined as re-serialisation, so hashing a different rendering of the same object than the one
     * transmitted produces a signature Midtrans cannot reproduce.
     *
     * Public because `SnapMidtransGatewayTest` asserts on it and Kotlin has no package-private.
     */
    fun chargeBody(orderId: String, grossAmount: BigDecimal, expiryMinutes: Int): String {
        // TWO decimal places, which INVERTS the legacy rule. HttpMidtransGateway documents that
        // Midtrans rejects a gross_amount with decimals for IDR; SNAP's amount.value is a decimal
        // string, and the sandbox probe confirmed it — "1500.00" produced a QR encoding 1500 IDR.
        val amount = grossAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val validUntil = OffsetDateTime.now(ZoneId.systemDefault())
            .plusMinutes(expiryMinutes.toLong())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        // Rendered with MidtransJson, so the bytes sent are the bytes the Jackson version sent:
        // compact, in this insertion order, Jackson's escaping. Compact means what is written here
        // is already minified and the hash covers exactly these bytes. The order is stable —
        // minify does not sort, so order is free to be whatever we choose, but it should not vary
        // run to run, or two logs of "the same" request stop being comparable while diagnosing a
        // rejected field.
        return MidtransJson.render(buildJsonObject {
            put("partnerReferenceNo", orderId)
            put("merchantId", merchantId)
            putJsonObject("amount") {
                put("value", amount)
                put("currency", "IDR")
            }
            put("validityPeriod", validUntil)
            putJsonObject("additionalInfo") { put("acquirer", acquirer) }
        })
    }

    /**
     * Asks Midtrans what actually happened to a charge — the reconciliation path for notifications
     * that never arrived.
     *
     * **The returned status carries no signature, and that is correct here** rather than a
     * gap: see [MidtransGateway.fetchedStatusCarriesItsOwnSignature], which this class
     * overrides to false. A webhook is inbound and unsolicited, so its signature is the only thing
     * separating it from a forgery; this response is to a request we ourselves signed, over TLS, to
     * a host we chose. Forging it means having already broken TLS, at which point the charge
     * response would be forgeable too.
     */
    override fun fetchStatus(orderId: String, referenceNo: String?): Optional<TransactionStatus> {
        val body = queryBody(orderId, referenceNo)
        val response = post(QUERY_PATH, body, orderId)

        val responseCode = MidtransJson.text(response, "responseCode")
        val snapStatus = MidtransJson.text(response, "latestTransactionStatus")
        if (QUERY_NOT_FOUND == responseCode || "07" == snapStatus) {
            // Distinct from "exists and is still pending": it means our charge call never landed.
            // The payment row exists precisely so that is discoverable (PaymentService writes it
            // before calling), and the legacy gateway reports the same case the same way.
            return Optional.empty()
        }
        if (QUERY_OK != responseCode) {
            throw SnapException("BI-SNAP status query failed for order " + orderId +
                ": responseCode=" + responseCode + " " + MidtransJson.text(response, "responseMessage"))
        }

        val legacyStatus = SnapNotification.toLegacyTransactionStatus(snapStatus)
        if (legacyStatus.isEmpty) {
            // Same reasoning as the notification path: 04 Refunded has no home in this platform,
            // and inventing one would put a wrong story in the audit trail. Reporting it as still
            // pending is honest — this sweep has not resolved anything.
            LOG.warnf("BI-SNAP status query for order %s returned '%s', which this platform has no " +
                "transition for; leaving the payment pending", orderId, snapStatus)
            return Optional.empty()
        }

        val amount = (response as? JsonObject)?.get("amount")
        return Optional.of(TransactionStatus(orderId, MidtransJson.text(response, "originalReferenceNo"),
            legacyStatus.get(), null, responseCode,
            MidtransJson.text(amount, "value"),
            // No signature: see the KDoc above and fetchedStatusCarriesItsOwnSignature.
            null))
    }

    override fun cancel(orderId: String, referenceNo: String?, reason: String) {
        val response = post(CANCEL_PATH, cancelBody(orderId, referenceNo, reason), orderId)
        val responseCode = MidtransJson.text(response, "responseCode")
        // SNAP codes are HTTP status + service code + case: 2007700 is success, anything not
        // starting 200 is a refusal (already paid, already expired, unknown order).
        if (responseCode == null || !responseCode.startsWith("200")) {
            throw SnapException("BI-SNAP cancel failed for order " + orderId +
                ": responseCode=" + responseCode + " " + MidtransJson.text(response, "responseMessage"))
        }
    }

    // originalReferenceNo is Midtrans's A1… id from the charge response. The sandbox refused a
    // cancel without it ("4007702 Invalid Mandatory Field originalExternalId/originalReferenceNo"),
    // and of the two we hold only this one: the charge's X-EXTERNAL-ID is random and never stored.
    // reason is the caller's: it was a fixed "Paid in cash at the counter", which was also sent
    // when a cancelled showing withdrew the QR.
    fun cancelBody(orderId: String, referenceNo: String?, reason: String): String = MidtransJson.render(buildJsonObject {
        put("originalPartnerReferenceNo", orderId)
        if (referenceNo != null) put("originalReferenceNo", referenceNo)
        put("merchantId", merchantId)
        put("reason", reason)
    })

    /**
     * BI-SNAP QR MPM refund, service 78. Shapes and codes from the sandbox
     * : `originalReferenceNo` is required in practice though the docs mark it
     * optional, several partial refunds of one charge are allowed, and a refund beyond what is left
     * answers 4037802.
     *
     * **Not provable before production** (BI-SNAP production access is not granted yet), so these are taken from
     * Midtrans's pages and not seen: refund windows differ by issuer (reportedly 45 days on-us
     * GoPay, 7 days off-us; 4057801 past it); ShopeePay refunds only in full and only 06:00–23:55
     * WIB; and 4037814 Insufficient Funds once Midtrans has paid the balance out to the merchant.
     * The sandbox pays only `gopay`-acquired QRs with GoPay, so no off-us refund was exercised.
     * Each of those arrives here as a [MidtransGateway.RefundResult.Refused] with its code, which is
     * what staff see.
     */
    override fun refund(orderId: String, referenceNo: String, partnerRefundNo: String, amount: BigDecimal,
                        reason: String, resend: Boolean): MidtransGateway.RefundResult {
        if (resend) {
            // A number that may have reached Midtrans already: X-EXTERNAL-ID is unique per day only,
            // so a resend on a later day is not guaranteed a 4097801. Look before sending again.
            val found = findInHistory(orderId, referenceNo, partnerRefundNo)
            if (found !is MidtransGateway.RefundResult.Transient || found.detail != NOT_IN_HISTORY) return found
        }
        val response = try {
            post(REFUND_PATH, refundBody(orderId, referenceNo, partnerRefundNo, amount, reason), orderId, partnerRefundNo)
        } catch (e: SnapException) {
            // No answer: timeout, connection, token, an unparsable body. The same number goes again.
            return MidtransGateway.RefundResult.Transient(e.message ?: e.toString())
        }
        val code = MidtransJson.text(response, "responseCode")
        val message = MidtransJson.text(response, "responseMessage")
        return when (code) {
            REFUND_OK -> {
                val refundNo = MidtransJson.text(response, "referenceNo")
                val refunded = MidtransJson.text((response as? JsonObject)?.get("refundAmount"), "value")
                    ?.toBigDecimalOrNull()
                if (refundNo != null && refunded != null) MidtransGateway.RefundResult.Refunded(refundNo, refunded)
                // Done, but without the id that counts it once against a '04': read it from the history.
                else findInHistory(orderId, referenceNo, partnerRefundNo)
            }
            // "Duplicate Transaction": this number was sent before. Only the history says whether it refunded.
            REFUND_DUPLICATE -> findInHistory(orderId, referenceNo, partnerRefundNo)
            null, "5007801", "5047800" -> MidtransGateway.RefundResult.Transient("responseCode=$code $message")
            else -> {
                LOG.warnf("BI-SNAP refused refund %s of order %s: %s %s", partnerRefundNo, orderId, code, message)
                MidtransGateway.RefundResult.Refused(code, message)
            }
        }
    }

    /** Our [partnerRefundNo] in the charge's `refundHistory[]`, as a [MidtransGateway.RefundResult.Refunded];
     * [NOT_IN_HISTORY] when the history is readable and lacks it, any other `Transient` when it cannot
     * be read. Never `Refused`: not finding a refund is no reason to stop trying it. */
    private fun findInHistory(orderId: String, referenceNo: String, partnerRefundNo: String): MidtransGateway.RefundResult {
        val response = try {
            post(QUERY_PATH, queryBody(orderId, referenceNo), orderId)
        } catch (e: SnapException) {
            return MidtransGateway.RefundResult.Transient(e.message ?: e.toString())
        }
        val code = MidtransJson.text(response, "responseCode")
        if (QUERY_OK != code) return MidtransGateway.RefundResult.Transient("query responseCode=$code")
        val history = GatewayRefund.history(response)
            ?: return MidtransGateway.RefundResult.Transient("query carried no readable refundHistory")
        val ours = history.firstOrNull { it.partnerRefundNo == partnerRefundNo }
            ?: return MidtransGateway.RefundResult.Transient(NOT_IN_HISTORY)
        return MidtransGateway.RefundResult.Refunded(ours.refundNo, ours.amount)
    }

    /** As [cancelBody]; `refundAmount` always, in [chargeBody]'s two decimals. */
    fun refundBody(orderId: String, referenceNo: String, partnerRefundNo: String, amount: BigDecimal,
                   reason: String): String = MidtransJson.render(buildJsonObject {
        put("merchantId", merchantId)
        put("originalPartnerReferenceNo", orderId)
        put("originalReferenceNo", referenceNo)
        put("partnerRefundNo", partnerRefundNo)
        putJsonObject("refundAmount") {
            put("value", amount.setScale(2, RoundingMode.HALF_UP).toPlainString())
            put("currency", "IDR")
        }
        put("reason", reason)
    })

    /** BI-SNAP responses are authenticated by TLS on a request we signed, not by a field. */
    override fun fetchedStatusCarriesItsOwnSignature(): Boolean = false

    // originalReferenceNo for the same reason as cancelBody: without it the sandbox answered
    // "not found" for QRs it had just generated and would still accept payment on.
    fun queryBody(orderId: String, referenceNo: String?): String = MidtransJson.render(buildJsonObject {
        put("originalPartnerReferenceNo", orderId)
        if (referenceNo != null) put("originalReferenceNo", referenceNo)
        put("merchantId", merchantId)
        // The service being asked about — 47 is QR MPM generate, i.e. the call that created
        // this transaction, not the code of the query itself.
        put("serviceCode", "47")
    })

    /** [externalId] is a fresh UUID unless the call is a refund, whose `partnerRefundNo` it carries. */
    private fun post(path: String, body: String, orderId: String,
                     externalId: String = UUID.randomUUID().toString()): JsonElement {
        if (!isConfigured()) {
            throw SnapException("BI-SNAP is not configured")
        }
        val token = tokens.token()
        val timestamp = tokens.timestamp()
        val signature = SnapSignatures.signTransactional("POST", path, token, body, timestamp,
            credentials.clientSecret())

        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-TIMESTAMP", timestamp)
            .header("X-SIGNATURE", signature)
            .header("X-PARTNER-ID", credentials.partnerId())
            // Must be unique per day. A fresh UUID per attempt rather than the order id,
            // because a retry after a timeout would otherwise collide with its own first
            // attempt and come back 4094700. Idempotency does not depend on this: it comes
            // from partnerReferenceNo, which Midtrans rejects as a duplicate, and from the
            // payment row itself. A refund is the exception: its X-EXTERNAL-ID is its
            // partnerRefundNo, so a resend is the same refund and comes back 4097801.
            .header("X-EXTERNAL-ID", externalId)
            .header("CHANNEL-ID", credentials.channelId())
            .header("X-DEVICE-ID", "booking-platform")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val raw = exchange(request, orderId)
        // Reported as it was when the parse still lived inside exchange().
        return try {
            MidtransJson.parseResponse(raw)
        } catch (e: Exception) {
            throw SnapException("BI-SNAP request failed for order $orderId", e)
        }
    }

    /**
     * Seam for tests, which must not open sockets — the same shape `StubMidtransGateway` uses
     * against the legacy gateway. Returns the raw response body, so a stub hands back Midtrans's
     * text and needs no JSON library. Public, so a Java override can be public too (Kotlin has no
     * package-private, and Java refuses to narrow an override).
     */
    fun exchange(request: HttpRequest, orderId: String): String {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SnapException("Interrupted calling BI-SNAP for order $orderId", e)
        } catch (e: Exception) {
            throw SnapException("BI-SNAP request failed for order $orderId", e)
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(SnapMidtransGateway::class.java)

        internal const val CHARGE_PATH = "/v1.0/qr/qr-mpm-generate"
        internal const val QUERY_PATH = "/v1.0/qr/qr-mpm-query"

        /** BI-SNAP QR MPM cancel, service code 77. Not yet exercised against Midtrans's sandbox. */
        internal const val CANCEL_PATH = "/v1.0/qr/qr-mpm-cancel"

        /** Success for QR MPM generate: HTTP 200, service 47, case 00. */
        internal const val CHARGE_OK = "2004700"

        /** Query is service 51, not 47 — the seven-digit code names the service being called, and
         * reusing the charge's code here is the mistake nearest to hand. */
        internal const val QUERY_OK = "2005100"
        internal const val QUERY_NOT_FOUND = "4045101"

        internal const val REFUND_PATH = "/v1.0/qr/qr-mpm-refund"
        internal const val REFUND_OK = "2007800"
        internal const val REFUND_DUPLICATE = "4097801"
        private const val NOT_IN_HISTORY = "not in the refund history"

        private fun isSet(value: String?): Boolean = value != null && !value.isJavaBlank() && "__unset__" != value
    }
}
