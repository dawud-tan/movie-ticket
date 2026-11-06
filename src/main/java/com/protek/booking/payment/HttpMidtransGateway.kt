package com.protek.booking.payment

import com.protek.booking.isJavaBlank
import com.protek.booking.payment.MidtransGateway.ChargeResult
import com.protek.booking.payment.MidtransGateway.TransactionStatus
import jakarta.annotation.PostConstruct
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Reserve
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.Optional

/**
 * Midtrans Core API over `java.net.http.HttpClient`.
 *
 * Plain JDK HTTP plus a JSON tree rather than a generated REST client, for the same reason
 * this codebase uses plain JDBC over an ORM: this is two calls against a stable API, and a
 * declarative client would add a code-generation and reflection surface to native-image in
 * exchange for saving perhaps thirty lines. The responses are read as a kotlinx
 * [JsonElement] tree (Jackson's `JsonNode` before 2026-10-08, see [MidtransJson]) rather than
 * bound to DTOs so that Midtrans adding fields cannot break deserialisation, and so there are no
 * extra classes to register for reflection.
 */
/* @Reserve so SnapMidtransGateway displaces it whenever midtrans.api=snap — which, since
 * 2026-08-17, is the shipped default. This is now the rollback path rather than the usual one:
 * it becomes the sole MidtransGateway again when built with midtrans.api=legacy (or any value
 * other than "snap"), and nothing about it changes when it does. LegacyGatewaySelectionTest
 * exercises that route, because a rollback nobody tries is one nobody can rely on.
 *
 * @Reserve is CDI 5's replacement for Quarkus 4's deprecated @DefaultBean, and it needs the
 * @Priority: a reserve without one is never selected, so the legacy build would have no
 * MidtransGateway at all. The value does not matter here, because any non-reserve bean (the
 * SNAP one) outranks every reserve whatever its priority. */
@Reserve
@Priority(0)
@ApplicationScoped
class HttpMidtransGateway : MidtransGateway {

    // Config is injected into public, nullable fields — the shape every converted payment class
    // uses, because the Java tests assign these directly and a null must not throw.
    @ConfigProperty(name = "midtrans.base-url")
    @JvmField
    final var baseUrl: String? = null

    @ConfigProperty(name = "midtrans.server-key")
    @JvmField
    final var serverKey: String? = null

    /**
     * QRIS acquirer. Midtrans requires one to be named for a QRIS charge; the QR itself is
     * interoperable across every QRIS-participating wallet and bank app regardless of which
     * acquirer minted it, so this is a routing detail, not a restriction on who can pay.
     */
    @ConfigProperty(name = "midtrans.qris.acquirer", defaultValue = "gopay")
    @JvmField
    final var acquirer: String? = null

    @ConfigProperty(name = "midtrans.http-timeout-seconds", defaultValue = "15")
    @JvmField
    final var timeoutSeconds: Int = 15

    private lateinit var httpClient: HttpClient

    @PostConstruct
    fun init() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            // HTTP/1.1 explicitly: Midtrans's API is HTTP/1.1, and letting the JDK
            // negotiate HTTP/2 adds an upgrade round-trip on every cold start for no gain
            // on a two-call integration.
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    }

    override fun isConfigured(): Boolean {
        val key = serverKey
        return key != null && !key.isJavaBlank() && UNSET != key
    }

    override fun chargeQris(orderId: String, grossAmount: BigDecimal, expiryMinutes: Int): ChargeResult {
        // Midtrans rejects a gross_amount with decimal places for IDR; it is a zero-decimal
        // currency, so the amount is sent as an integer. Sending "50000.00" is a 400 that
        // would otherwise only surface at the first real charge.
        //
        // Insertion order now, where the Jackson version's Map.of had a per-JVM random order. The
        // legacy Core API does not sign its request body, so the order was never load-bearing;
        // a stable one just makes two logs of the same request comparable.
        val body = MidtransJson.render(buildJsonObject {
            put("payment_type", "qris")
            putJsonObject("transaction_details") {
                put("order_id", orderId)
                put("gross_amount", grossAmount.setScale(0, RoundingMode.HALF_UP).longValueExact())
            }
            putJsonObject("qris") { put("acquirer", acquirer) }
            putJsonObject("custom_expiry") {
                put("expiry_duration", expiryMinutes)
                put("unit", "minute")
            }
        })

        val response = send("POST", "/v2/charge", body, orderId)
        val statusCode = MidtransJson.text(response, "status_code")
        // 201 = created and pending payment, which is the expected outcome for QRIS. Anything
        // else is a charge that will never be payable, so failing here keeps a payment row from
        // sitting pending forever with no QR to scan.
        if ("201" != statusCode) {
            throw MidtransException("Midtrans refused the charge for order " + orderId +
                ": status_code=" + statusCode + " " + MidtransJson.text(response, "status_message"))
        }
        return ChargeResult(MidtransJson.text(response, "transaction_id"), extractQrContent(response),
            MidtransJson.text(response, "transaction_status"))
    }

    /**
     * The QR payload lives in `actions[]`, not a top-level field. Preferring the
     * `generate-qr-code` action's URL is deliberate: Midtrans hosts the rendered QR
     * image there, so the browser can display it with an `<img>` tag and this codebase
     * needs no QR-encoding library — one fewer dependency, and one fewer thing to get wrong
     * about a payment instrument.
     */
    private fun extractQrContent(response: JsonElement): String? {
        val actions = (response as? JsonObject)?.get("actions")
        if (actions is JsonArray) {
            for (action in actions) {
                if ("generate-qr-code" == MidtransJson.text(action, "name")) {
                    // Returned as found, null included, exactly as the Java version did.
                    return MidtransJson.text(action, "url")
                }
            }
        }
        // Some Midtrans responses carry the raw QR string instead; keep it rather than failing,
        // since a raw payload is still renderable client-side if it ever comes back that way.
        return MidtransJson.text(response, "qr_string")
            ?: throw MidtransException("Charge response contained neither a generate-qr-code " +
                "action nor a qr_string; nothing to show the customer")
    }

    // referenceNo is BI-SNAP's; the Core API keys on the order id alone.
    override fun fetchStatus(orderId: String, referenceNo: String?): Optional<TransactionStatus> {
        val response = try {
            send("GET", "/v2/$orderId/status", null, orderId)
        } catch (e: MidtransNotFoundException) {
            return Optional.empty()
        }
        return Optional.of(TransactionStatus(
            MidtransJson.text(response, "order_id"),
            MidtransJson.text(response, "transaction_id"),
            MidtransJson.text(response, "transaction_status"),
            MidtransJson.text(response, "fraud_status"),
            MidtransJson.text(response, "status_code"),
            MidtransJson.text(response, "gross_amount"),
            MidtransJson.text(response, "signature_key")))
    }

    /** Core API `POST /v2/{order_id}/cancel`. No body, so [reason] has nowhere to go; Midtrans
     * answers 200 with `transaction_status: cancel` and sends the same as a notification. */
    override fun cancel(orderId: String, referenceNo: String?, reason: String) {
        send("POST", "/v2/$orderId/cancel", "", orderId)
    }

    /** The rollback path builds no Core API refund: a debt on a payment this gateway took stays
     * on `/admin/payments` for staff, as every debt did before BI-SNAP refunds existed. */
    override fun refund(orderId: String, referenceNo: String, partnerRefundNo: String, amount: BigDecimal,
                        reason: String, resend: Boolean): MidtransGateway.RefundResult =
        MidtransGateway.RefundResult.Refused(null, "the legacy Core API gateway does not refund")

    private fun send(method: String, path: String, body: String?, orderId: String): JsonElement {
        if (!isConfigured()) {
            throw MidtransException("Midtrans is not configured (midtrans.server-key unset)")
        }
        // Basic auth with the server key as the username and an EMPTY password — Midtrans's
        // documented scheme. The trailing colon is required; omitting it authenticates as a
        // different, non-existent credential and returns 401.
        val credentials = Base64.getEncoder()
            .encodeToString(("$serverKey:").toByteArray(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Authorization", "Basic $credentials")
            .header("Accept", "application/json")
        if (body == null) {
            request.GET()
        } else {
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        }

        val response: HttpResponse<String> = try {
            httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw MidtransException("Midtrans $method $path failed for order $orderId", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MidtransException("Interrupted calling Midtrans for order $orderId", e)
        }

        val parsed = try {
            MidtransJson.parseResponse(response.body())
        } catch (e: IllegalArgumentException) {
            throw MidtransException("Midtrans returned unparseable JSON (HTTP " +
                response.statusCode() + ") for order " + orderId, e)
        }
        // Midtrans signals "no such transaction" as HTTP 404 with status_code 404, which is a
        // legitimate reconciliation answer ("we never received this charge") rather than an
        // error — distinguished here so fetchStatus can return empty instead of throwing.
        if (response.statusCode() == 404 || "404" == MidtransJson.text(parsed, "status_code")) {
            throw MidtransNotFoundException("Midtrans has no record of order $orderId")
        }
        if (response.statusCode() >= 400) {
            throw MidtransException("Midtrans " + method + " " + path + " returned HTTP " +
                response.statusCode() + ": " + MidtransJson.text(parsed, "status_message"))
        }
        LOG.debugf("Midtrans %s %s -> status_code=%s", method, path, MidtransJson.text(parsed, "status_code"))
        return parsed
    }

    /** Unchecked so the two call sites can decide: the charge path lets it surface (a booking
     * that cannot produce a QR should say so), and the reconciliation sweep catches it per
     * payment so one unreachable transaction does not stop the batch. */
    open class MidtransException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    private class MidtransNotFoundException(message: String) : MidtransException(message)

    companion object {
        private val LOG: Logger = Logger.getLogger(HttpMidtransGateway::class.java)
        private const val UNSET = "__unset__"
    }
}
