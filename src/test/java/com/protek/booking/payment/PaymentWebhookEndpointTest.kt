package com.protek.booking.payment

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The webhook endpoint's HTTP contract.
 *
 * What these assert is mostly about *status codes*, which sounds cosmetic and is not:
 * Midtrans retries any non-2xx response, so the codes decide whether a given situation resolves
 * or turns into an indefinite retry loop against this endpoint. Getting them wrong is an
 * availability problem for the platform and a settlement-delay problem for the tenant.
 *
 * Runs under its own profile setting a concrete `midtrans.server-key`, because the
 * default is the "unset" sentinel and an unconfigured verifier correctly rejects everything with
 * 401 — which would make "did the request reach the handler?" and "was the signature bad?"
 * indistinguishable from the outside.
 */
@QuarkusTest
@TestProfile(PaymentWebhookEndpointTest.WebhookKeyProfile::class)
class PaymentWebhookEndpointTest {

    companion object {
        const val SERVER_KEY = "SB-Mid-server-WEBHOOKTESTKEY"

        private fun signature(orderId: String, statusCode: String, grossAmount: String): String {
            try {
                val digest = MessageDigest.getInstance("SHA-512")
                return HexFormat.of().formatHex(digest.digest(
                    (orderId + statusCode + grossAmount + SERVER_KEY).toByteArray(StandardCharsets.UTF_8)))
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun body(orderId: String, statusCode: String, grossAmount: String, sig: String): String =
            "{\"order_id\":\"" + orderId + "\",\"status_code\":\"" + statusCode +
                "\",\"gross_amount\":\"" + grossAmount + "\",\"transaction_status\":\"settlement\"" +
                ",\"transaction_id\":\"midtrans-test\",\"fraud_status\":\"accept\"" +
                ",\"payment_type\":\"qris\",\"signature_key\":\"" + sig + "\"}"
    }

    class WebhookKeyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("midtrans.server-key" to SERVER_KEY)
    }

    @Test
    fun validlySignedNotification_reachesTheHandlerWithoutAnyAuthentication() {
        // The point of this test: a 404 proves the request passed the security layer AND passed
        // signature verification, reaching the order lookup — while carrying no session, no
        // bearer token, and no identity of any kind. That is exactly the property the endpoint
        // needs, and it cannot be shown with a deliberately-invalid signature, because 401 would
        // then be the correct answer and indistinguishable from being blocked by @Authenticated.
        val orderId = "BP-does-not-exist"
        given().contentType("application/json")
            .body(body(orderId, "200", "50000.00", signature(orderId, "200", "50000.00")))
            .`when`().post("/payments/midtrans")
            .then().statusCode(404)
    }

    @Test
    fun invalidSignature_is401() {
        val orderId = "BP-forged"
        given().contentType("application/json")
            .body(body(orderId, "200", "50000.00", "0".repeat(128)))
            .`when`().post("/payments/midtrans")
            .then().statusCode(401)
    }

    @Test
    fun signatureOverDifferentValues_is401() {
        // A captured signature replayed against a different amount: the attack the signature
        // exists to stop, exercised over real HTTP rather than only at the verifier's unit level.
        val orderId = "BP-replayed"
        given().contentType("application/json")
            .body(body(orderId, "200", "10000.00", signature(orderId, "200", "50000.00")))
            .`when`().post("/payments/midtrans")
            .then().statusCode(401)
    }

    @Test
    fun malformedJson_is400() {
        // 400 rather than 500: a body we cannot parse can never succeed on retry, and a 500 would
        // have Midtrans retrying it indefinitely.
        given().contentType("application/json")
            .body("not json at all")
            .`when`().post("/payments/midtrans")
            .then().statusCode(400)
    }

    @Test
    fun emptyBody_is401_ratherThanCrashing() {
        // An empty object has no signature, so it fails verification. The important part is that
        // it fails closed with a status code rather than throwing a NullPointerException into a
        // 500 that Midtrans would retry.
        given().contentType("application/json")
            .body("{}")
            .`when`().post("/payments/midtrans")
            .then().statusCode(401)
    }
}
