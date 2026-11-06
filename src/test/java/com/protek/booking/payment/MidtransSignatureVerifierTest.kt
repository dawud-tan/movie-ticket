package com.protek.booking.payment

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/**
 * Focused tests for the one control that authenticates the webhook write path.
 *
 * A plain unit test, not a `@QuarkusTest`: this is pure computation, and running it
 * without a container makes it fast enough to be worth having many cases — which matters,
 * because each case here is a distinct way an implementation of this can be subtly wrong while
 * appearing to work against a happy-path notification.
 */
class MidtransSignatureVerifierTest {

    companion object {
        private const val SERVER_KEY = "SB-Mid-server-EXAMPLEKEY123456"
    }

    private fun verifier(key: String?): MidtransSignatureVerifier {
        val v = MidtransSignatureVerifier()
        v.serverKey = key
        return v
    }

    private fun signature(orderId: String, statusCode: String, grossAmount: String, key: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-512")
            return HexFormat.of().formatHex(digest.digest(
                (orderId + statusCode + grossAmount + key).toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    @Test
    fun validSignature_isAccepted() {
        val orderId = "BP-1234"
        val statusCode = "200"
        val grossAmount = "50000.00"
        assertTrue(verifier(SERVER_KEY).verify(orderId, statusCode, grossAmount,
            signature(orderId, statusCode, grossAmount, SERVER_KEY)))
    }

    @Test
    fun signatureFromADifferentServerKey_isRejected() {
        // The server key is the shared secret and the last field in the hash input, so knowing
        // the other three — all of which appear in the notification body — must not be enough.
        val orderId = "BP-1234"
        assertFalse(verifier(SERVER_KEY).verify(orderId, "200", "50000.00",
            signature(orderId, "200", "50000.00", "SB-Mid-server-WRONGKEY")))
    }

    @Test
    fun tamperedFields_areEachRejected() {
        val orderId = "BP-1234"
        val valid = signature(orderId, "200", "50000.00", SERVER_KEY)
        val v = verifier(SERVER_KEY)

        // Each of these is a real attack shape: pointing a captured signature at a different
        // order, upgrading a pending status to settled, or claiming a smaller amount was due.
        assertFalse(v.verify("BP-9999", "200", "50000.00", valid), "different order id")
        assertFalse(v.verify(orderId, "201", "50000.00", valid), "different status code")
        assertFalse(v.verify(orderId, "200", "10000.00", valid), "different amount")
    }

    @Test
    fun grossAmountFormatting_mustMatchMidtransExactly() {
        // Midtrans hashes its own textual representation ("50000.00"). Parsing the amount and
        // re-rendering it ("50000") produces a different digest and would reject every genuine
        // notification — a failure that looks like an attack rather than a formatting bug, which
        // is exactly why this case is pinned down by a test.
        val orderId = "BP-1234"
        val midtransSignature = signature(orderId, "200", "50000.00", SERVER_KEY)
        val v = verifier(SERVER_KEY)

        assertTrue(v.verify(orderId, "200", "50000.00", midtransSignature))
        assertFalse(v.verify(orderId, "200", "50000", midtransSignature),
            "re-formatted amount must not validate — proving the raw string is what is hashed")
    }

    @Test
    fun hexCaseIsIgnored_butContentIsNot() {
        val orderId = "BP-1234"
        val valid = signature(orderId, "200", "50000.00", SERVER_KEY)
        val v = verifier(SERVER_KEY)
        // Hex digests differ only by case across implementations; rejecting an uppercase but
        // otherwise-correct signature would be a spurious failure, so case is normalised.
        // Java's toUpperCase() used the default locale; Kotlin's uppercase() would use ROOT.
        assertTrue(v.verify(orderId, "200", "50000.00", valid.uppercase(Locale.getDefault())))
    }

    @Test
    fun missingOrNullInputs_areRejectedRatherThanThrowing() {
        val v = verifier(SERVER_KEY)
        // A malformed notification must fail closed. Throwing here would surface as a 500 and,
        // because Midtrans retries non-2xx responses, would turn one bad body into a retry loop.
        assertFalse(v.verify(null, "200", "50000.00", "abc"))
        assertFalse(v.verify("BP-1", null, "50000.00", "abc"))
        assertFalse(v.verify("BP-1", "200", null, "abc"))
        assertFalse(v.verify("BP-1", "200", "50000.00", null))
    }

    @Test
    fun unconfiguredVerifier_rejectsEverything() {
        // With no server key there is no way to authenticate a notification, so the only safe
        // answer is no. Accepting unverified notifications when unconfigured would mean enabling
        // the webhook endpoint before the secret is set silently trusts anyone.
        assertFalse(verifier("__unset__").isConfigured())
        assertFalse(verifier("__unset__").verify("BP-1", "200", "50000.00",
            signature("BP-1", "200", "50000.00", "__unset__")))
        assertFalse(verifier(null).verify("BP-1", "200", "50000.00", "whatever"))
        assertFalse(verifier("  ").verify("BP-1", "200", "50000.00", "whatever"))
    }
}
