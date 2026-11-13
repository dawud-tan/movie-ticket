package com.protek.booking.payment.snap

import com.protek.booking.payment.MidtransGateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/**
 * Pins the BI-SNAP QRIS charge.
 *
 * The request shape here was proven against sandbox by a probe script before
 * any of it was written — response code 2004700 and a scannable QR — so these tests lock in a known-good
 * shape rather than a guess at one.
 */
class SnapMidtransGatewayTest {

    @TempDir
    lateinit var dir: Path

    companion object {
        private val OK_RESPONSE = """
            {"responseCode":"2004700","responseMessage":"Successful",
             "referenceNo":"A120260815065321jRQHeFQh2mID",
             "partnerReferenceNo":"BP-1",
             "qrContent":"00020101021226620014COM.GO-JEK.WWW",
             "qrUrl":"https://merchants-app.sbx.midtrans.com/v3/qris/gopay/A12/qr-code",
             "qrImage":"iVBORw0KGgo"}""".trimIndent()

        /** Drains an `ofString` body publisher, so assertions can be made against the bytes that
         * would actually go on the wire rather than against what the gateway intended to send. */
        private fun bodyOf(request: HttpRequest): String {
            val body = StringBuilder()
            val done = CountDownLatch(1)
            request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: ByteBuffer) {
                    body.append(StandardCharsets.UTF_8.decode(item))
                }

                override fun onError(throwable: Throwable) {
                    done.countDown()
                }

                override fun onComplete() {
                    done.countDown()
                }
            })
            assertTrue(done.await(5, TimeUnit.SECONDS), "body publisher did not complete")
            return body.toString()
        }
    }

    class StubGateway : SnapMidtransGateway() {
        var captured: HttpRequest? = null
        var response = OK_RESPONSE
        /** When not empty, answers in order ahead of [response]; every request is kept in [sent]. */
        val responses = ArrayDeque<String>()
        val sent = mutableListOf<HttpRequest>()

        // Hands back Midtrans's text; the gateway parses it.
        override fun exchange(request: HttpRequest, orderId: String): String {
            captured = request
            sent += request
            return responses.removeFirstOrNull() ?: response
        }
    }

    @Test
    fun theAmountCarriesTwoDecimals_invertingTheLegacyRule() {
        val gateway = gateway()
        val body = gateway.chargeBody("BP-1", BigDecimal("50000"), 9)

        assertTrue(body.contains("\"value\":\"50000.00\""),
            "SNAP's amount.value is a decimal string, and the sandbox probe confirmed it: " +
                "\"1500.00\" produced a QR encoding 1500 IDR. HttpMidtransGateway " +
                "documents the OPPOSITE for the legacy API — IDR rejected with decimal " +
                "places — so this is the one number most likely to be copied wrongly. " +
                "Actual body: " + body)
    }

    @Test
    fun theBodyIsMinifiedAsSent_soTheHashCoversExactlyTheseBytes() {
        val gateway = gateway()
        val body = gateway.chargeBody("BP-1", BigDecimal("50000"), 9)

        assertEquals(SnapSignatures.minify(body), body,
            "minify is defined as re-serialisation, so the body must already be minified when " +
                "it is both hashed and sent; anything else risks hashing a different " +
                "rendering from the one transmitted")
        assertFalse(body.contains("\n"))
    }

    @Test
    fun theSignatureCoversTheContentActuallyTransmitted() {
        // Reconstructed from what was actually transmitted — body, timestamp and token — rather
        // than from what the gateway meant to send, because a mismatch between those two fails
        // identically to a wrong client secret.
        //
        // "Content", not "bytes", and the distinction was established by mutation rather than
        // assumed: appending a newline to the transmitted body does NOT break the signature,
        // because minify is applied on both sides and normalises whitespace away. Substituting a
        // different amount does break it. So the invariant this pins is that the signed and sent
        // documents are the same document — which is the one that matters, since the attack worth
        // caring about is a changed amount, not a changed indentation.
        val gateway = gateway()
        gateway.chargeQris("BP-1", BigDecimal("50000"), 9)

        val request = gateway.captured!!
        val sentBody = bodyOf(request)
        val timestamp = request.headers().firstValue("X-TIMESTAMP").orElseThrow()
        val signature = request.headers().firstValue("X-SIGNATURE").orElseThrow()

        assertEquals(SnapSignatures.signTransactional("POST", SnapMidtransGateway.CHARGE_PATH,
                "test-token", sentBody, timestamp, "SECRET-1"),
            signature)
    }

    @Test
    fun everyMandatoryTransactionalHeaderIsPresent_includingTheOneWithNoXPrefix() {
        val gateway = gateway()
        gateway.chargeQris("BP-1", BigDecimal("50000"), 9)
        val request = gateway.captured!!

        assertEquals("Bearer test-token", request.headers().firstValue("Authorization").orElseThrow())
        assertEquals("G000000000", request.headers().firstValue("X-PARTNER-ID").orElseThrow(),
            "a single merchant sends its Merchant ID here — settled by measurement, since the " +
                "docs deadlock on it")
        assertEquals("12345", request.headers().firstValue("CHANNEL-ID").orElseThrow(),
            "CHANNEL-ID carries no X- prefix, unlike every header beside it")
        assertTrue(request.headers().firstValue("X-DEVICE-ID").isPresent,
            "mandatory despite this being a server-to-server call")
        assertTrue(request.headers().firstValue("X-EXTERNAL-ID").isPresent)
    }

    @Test
    fun twoChargesGetDifferentExternalIds_soARetryCannotCollideWithItself() {
        val gateway = gateway()
        gateway.chargeQris("BP-1", BigDecimal("50000"), 9)
        val first = gateway.captured!!.headers().firstValue("X-EXTERNAL-ID").orElseThrow()
        gateway.chargeQris("BP-1", BigDecimal("50000"), 9)
        val second = gateway.captured!!.headers().firstValue("X-EXTERNAL-ID").orElseThrow()

        // X-EXTERNAL-ID must be unique per day; reusing the order id would make a retry after a
        // timeout collide with its own first attempt and return 4094700. Idempotency comes from
        // partnerReferenceNo and the payment row, not from this header.
        assertNotEquals(first, second)
    }

    @Test
    fun aSuccessfulChargeMapsReferenceNoAndPrefersTheHostedQrUrl() {
        val gateway = gateway()
        val result = gateway.chargeQris("BP-1", BigDecimal("50000"), 9)

        assertEquals("A120260815065321jRQHeFQh2mID", result.transactionId)
        // qrUrl over qrContent keeps the "no QR-encoding library" decision intact: the
        // browser renders Midtrans's hosted image with an <img>.
        assertTrue(result.qrContent!!.startsWith("https://"), result.qrContent)
        assertEquals("pending", result.transactionStatus)
    }

    @Test
    fun whenOnlyARawQrStringComesBack_itIsUsedRatherThanFailing() {
        val gateway = gateway()
        gateway.response = "{\"responseCode\":\"2004700\",\"referenceNo\":\"A12\"," +
            "\"qrContent\":\"00020101021226620014COM.GO-JEK.WWW\"}"

        assertEquals("00020101021226620014COM.GO-JEK.WWW",
            gateway.chargeQris("BP-1", BigDecimal("50000"), 9).qrContent,
            "a raw EMV payload is still renderable client-side, so it beats failing a booking")
    }

    @Test
    fun aRefusedChargeThrowsWithMidtransResponseCode_ratherThanLeavingAPendingRowWithNoQr() {
        val gateway = gateway()
        gateway.response = "{\"responseCode\":\"4004702\",\"responseMessage\":\"Invalid Mandatory Field\"}"

        val e = assertThrows(SnapAccessTokenProvider.SnapException::class.java) {
            gateway.chargeQris("BP-1", BigDecimal("50000"), 9)
        }
        assertTrue(e.message!!.contains("4004702"), e.message)
    }

    @Test
    fun aSettledQueryMapsToTheLegacyVocabulary_carryingNoSignature() {
        val gateway = gateway()
        gateway.response = "{\"responseCode\":\"2005100\",\"responseMessage\":\"Successful\"," +
            "\"originalReferenceNo\":\"A12\",\"latestTransactionStatus\":\"00\"," +
            "\"amount\":{\"value\":\"50000.00\",\"currency\":\"IDR\"}}"

        val status = gateway.fetchStatus("BP-1", "A12").orElseThrow()
        assertEquals("settlement", status.transactionStatus,
            "PaymentService speaks the legacy vocabulary, and one transition implementation " +
                "must serve both APIs or they will drift apart")
        assertNull(status.signatureKey)
    }

    @Test
    fun theGatewayDeclaresThatItsStatusesCarryNoSignature() {
        // Load-bearing, not informational: PaymentReconciliationService gates its signature check
        // on this flag rather than on whether a signature happens to be present, because a
        // null-check would silently skip verification the day the LEGACY gateway returned null by
        // accident. The default is true, so only a gateway that has reasoned about it opts out —
        // and the reasoning is that a query response is answered over TLS to a request we
        // ourselves signed, unlike a webhook which anyone can POST.
        assertFalse(gateway().fetchedStatusCarriesItsOwnSignature())
    }

    @Test
    fun anOrderMidtransHasNoRecordOf_comesBackEmptyRatherThanAsAFailure() {
        // Distinct from "exists and is still pending": it means our charge call never landed. The
        // payment row exists precisely so that case is discoverable, and the legacy gateway
        // reports it identically.
        val gateway = gateway()
        gateway.response = "{\"responseCode\":\"4045101\",\"responseMessage\":\"Transaction Not Found\"}"
        assertTrue(gateway.fetchStatus("BP-1", "A12").isEmpty)

        gateway.response = "{\"responseCode\":\"2005100\",\"latestTransactionStatus\":\"07\"}"
        assertTrue(gateway.fetchStatus("BP-1", "A12").isEmpty)
    }

    @Test
    fun aRefundedQuery_resolvesNothing_ratherThanInventingATransition() {
        // '04' has no home: payment.status has a 'refunded' value nothing ever sets, and forcing
        // it onto 'failure' would put a wrong story into the audit trail. Reporting "unresolved"
        // is honest — the sweep genuinely has not resolved anything.
        val gateway = gateway()
        gateway.response = "{\"responseCode\":\"2005100\",\"latestTransactionStatus\":\"04\"}"
        assertTrue(gateway.fetchStatus("BP-1", "A12").isEmpty)
    }

    @Test
    fun theQueryAsksAboutServiceCode47_theCallThatCreatedTheTransaction() {
        // 47 is QR MPM generate — the service being asked about, not the code of the query itself
        // (which is 51). Getting this backwards is the mistake nearest to hand.
        val body = gateway().queryBody("BP-1", "A12")
        assertTrue(body.contains("\"serviceCode\":\"47\""), body)
        assertTrue(body.contains("\"originalPartnerReferenceNo\":\"BP-1\""), body)
    }

    @Test
    fun theQueryAndTheCancelBothCarryMidtransOwnReference_andOmitItWhenTheChargeNeverAnswered() {
        // Without originalReferenceNo the sandbox answered "not found" for live QRs, and refused a
        // cancel with "4007702 Invalid Mandatory Field originalExternalId/originalReferenceNo".
        val gateway = gateway()
        for (body in listOf(gateway.queryBody("BP-1", "A12"), gateway.cancelBody("BP-1", "A12", "r"))) {
            assertTrue(body.contains("\"originalReferenceNo\":\"A12\""), body)
            assertTrue(body.contains("\"originalPartnerReferenceNo\":\"BP-1\""), body)
        }
        for (body in listOf(gateway.queryBody("BP-1", null), gateway.cancelBody("BP-1", null, "r"))) {
            assertFalse(body.contains("originalReferenceNo\""), body)
            assertTrue(body.contains("\"originalPartnerReferenceNo\":\"BP-1\""), body)
        }
    }

    @Test
    fun theCancelCarriesTheReasonItIsGiven_soACancelledShowingIsNotReportedAsCash() {
        // The reason was a fixed "Paid in cash at the counter", sent for a cancelled showing too.
        val body = gateway().cancelBody("BP-1", "A12", "its showing was cancelled")
        assertTrue(body.contains("\"reason\":\"its showing was cancelled\""), body)
    }

    @Test
    fun initiatedAndPayingBothReadAsPending() {
        // The query set includes 01 Initiated and 02 Paying, which the notification set does not.
        // Both mean "the customer has not finished", which PaymentService treats as a no-op.
        for (snapStatus in java.util.List.of("01", "02", "03")) {
            val gateway = gateway()
            gateway.response = "{\"responseCode\":\"2005100\",\"latestTransactionStatus\":\"" +
                snapStatus + "\"}"
            assertEquals("pending", gateway.fetchStatus("BP-1", "A12").orElseThrow().transactionStatus,
                "status " + snapStatus)
        }
    }

    // the refund call (service 78). Answers as the sandbox gave them.

    private val refunded = """{"originalReferenceNo":"A12","referenceNo":"A1RF","responseMessage":"Successful",
        "partnerRefundNo":"pr1","refundAmount":{"currency":"IDR","value":"500.00"},"responseCode":"2007800"}"""

    private fun queryWithHistory(partnerRefundNo: String) = """{"responseCode":"2005100","latestTransactionStatus":"04",
        "amount":{"value":"1500.00","currency":"IDR"},"additionalInfo":{"refundHistory":[{"refundNo":"A1RF",
        "partnerReferenceNo":"$partnerRefundNo","refundStatus":"00","refundAmount":{"value":"500.00","currency":"IDR"}}]}}"""

    @Test
    fun aRefundAlwaysSendsItsAmount_andCarriesItsPartnerRefundNoAsTheExternalId() {
        // Without refundAmount the sandbox refunded the charge's whole remainder.
        val gateway = gateway()
        gateway.response = refunded
        val result = gateway.refund("BP-1", "A12", "pr1", BigDecimal("500"), "showing cancelled", false)

        assertEquals(MidtransGateway.RefundResult.Refunded("A1RF", BigDecimal("500.00")), result)
        val request = gateway.captured!!
        assertTrue(request.uri().path.endsWith("/v1.0/qr/qr-mpm-refund"), request.uri().toString())
        assertEquals("pr1", request.headers().firstValue("X-EXTERNAL-ID").orElseThrow())
        val body = bodyOf(request)
        assertTrue(body.contains("\"refundAmount\":{\"value\":\"500.00\",\"currency\":\"IDR\"}"), body)
        assertTrue(body.contains("\"originalReferenceNo\":\"A12\""), body)
        assertTrue(body.contains("\"partnerRefundNo\":\"pr1\""), body)
    }

    @Test
    fun aDuplicateRefund_isResolvedByTheChargesHistory_notTakenAsARefusal() {
        val gateway = gateway()
        gateway.responses += """{"responseCode":"4097801","responseMessage":"Duplicate Transaction"}"""
        gateway.responses += queryWithHistory("pr1")
        assertEquals(MidtransGateway.RefundResult.Refunded("A1RF", BigDecimal("500.00")),
            gateway.refund("BP-1", "A12", "pr1", BigDecimal("500"), "r", false))
        assertTrue(gateway.sent[1].uri().path.endsWith("/qr-mpm-query"))

        val missing = gateway()
        missing.responses += """{"responseCode":"4097801"}"""
        missing.responses += queryWithHistory("someone-else")
        assertTrue(missing.refund("BP-1", "A12", "pr1", BigDecimal("500"), "r", false)
            is MidtransGateway.RefundResult.Transient, "not in the history: try again, never 'refunded'")
    }

    @Test
    fun aResend_looksInTheHistoryFirst_andSendsNothingWhenItIsThere() {
        val gateway = gateway()
        gateway.response = queryWithHistory("pr1")
        assertEquals(MidtransGateway.RefundResult.Refunded("A1RF", BigDecimal("500.00")),
            gateway.refund("BP-1", "A12", "pr1", BigDecimal("500"), "r", true))
        assertEquals(1, gateway.sent.size, "only the query went out")

        val notThere = gateway()
        notThere.responses += queryWithHistory("other")
        notThere.responses += refunded
        assertTrue(notThere.refund("BP-1", "A12", "pr1", BigDecimal("500"), "r", true)
            is MidtransGateway.RefundResult.Refunded)
        assertTrue(notThere.sent[1].uri().path.endsWith("/qr-mpm-refund"))
    }

    @Test
    fun retryableCodesAndNoAnswerAreTransient_everyOtherCodeIsARefusalKeptWithItsCode() {
        for (code in listOf("5007801", "5047800")) {
            val gateway = gateway()
            gateway.response = """{"responseCode":"$code","responseMessage":"x"}"""
            assertTrue(gateway.refund("BP-1", "A12", "pr1", BigDecimal.ONE, "r", false)
                is MidtransGateway.RefundResult.Transient, code)
        }
        val silent = gateway()
        silent.response = ""
        assertTrue(silent.refund("BP-1", "A12", "pr1", BigDecimal.ONE, "r", false) is MidtransGateway.RefundResult.Transient)
        for (code in listOf("4037814", "4057801", "4037802", "4047801")) {
            val gateway = gateway()
            gateway.response = """{"responseCode":"$code","responseMessage":"Midtrans says no"}"""
            assertEquals(MidtransGateway.RefundResult.Refused(code, "Midtrans says no"),
                gateway.refund("BP-1", "A12", "pr1", BigDecimal.ONE, "r", false))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun gateway(): StubGateway {
        val gateway = StubGateway()
        gateway.credentials = credentials()
        gateway.tokens = fixedTokenProvider(gateway.credentials)
        gateway.baseUrl = "https://merchants.sbx.example"
        gateway.merchantId = "G000000000"
        gateway.acquirer = "gopay"
        gateway.timeoutSeconds = 5
        return gateway
    }

    private fun fixedTokenProvider(credentials: SnapCredentials): SnapAccessTokenProvider {
        return object : SnapAccessTokenProvider() {
            override fun token(): String = "test-token"

            override fun now(): Instant = Instant.parse("2026-08-15T00:00:00Z")
        }
    }

    private fun credentials(): SnapCredentials {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val credentials = SnapCredentials()
        credentials.clientId = "CLIENT-1"
        credentials.clientSecret = "SECRET-1"
        credentials.partnerId = "G000000000"
        credentials.channelId = "12345"
        credentials.privateKeyPath = writePem(generator.generateKeyPair().private.encoded,
            "PRIVATE KEY", "k.pem")
        credentials.midtransPublicKeyPath = writePem(generator.generateKeyPair().public.encoded,
            "PUBLIC KEY", "m.pem")
        credentials.load()
        return credentials
    }

    private fun writePem(der: ByteArray, label: String, name: String): String {
        val file = dir.resolve(name)
        Files.writeString(file, "-----BEGIN " + label + "-----\n" +
            Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + label + "-----\n")
        return file.toString()
    }
}
