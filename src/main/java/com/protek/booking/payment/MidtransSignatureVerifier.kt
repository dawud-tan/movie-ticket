package com.protek.booking.payment

import com.protek.booking.isJavaBlank
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat

/**
 * Verifies Midtrans's notification signature: `SHA512(order_id + status_code +
 * gross_amount + ServerKey)`, compared against the `signature_key` field.
 *
 * **Why this is the whole security model for the webhook, and not an extra check on top
 * of one** ("signature verification as a write-path authentication
 * requirement"): the notification endpoint is necessarily public — Midtrans's servers call it
 * with no session, no bearer token, and from addresses this platform does not control — and it
 * moves a reservation from `held` to `confirmed`, which is the transition someone
 * would forge if they could. The signature is the only thing distinguishing a real settlement
 * from a POST anyone on the internet can compose, so the endpoint must reject an unverifiable
 * body before reading anything else out of it.
 *
 * The server key is the shared secret. Because it is the last field in the hash input,
 * knowing the other three (all of which appear in a notification body) does not let an
 * attacker produce a valid signature.
 */
@ApplicationScoped
class MidtransSignatureVerifier {

    /** A public, nullable field rather than `lateinit`: `TestSignatureVerifier` and
     * `MidtransSignatureVerifierTest` assign it directly, including null, and a null must read as
     * "not configured" rather than throw. `final` because all-open would otherwise make it open,
     * which `@JvmField` refuses. */
    @ConfigProperty(name = "midtrans.server-key")
    @JvmField
    final var serverKey: String? = null

    fun isConfigured(): Boolean {
        val key = serverKey
        return key != null && !key.isJavaBlank() && UNSET != key
    }

    /**
     * @param grossAmount must be the string exactly as Midtrans sent it. Midtrans hashes its
     *                    own textual representation ("50000.00"), so re-formatting it from a
     *                    parsed [java.math.BigDecimal] produces a different digest and
     *                    rejects every genuine notification — a failure that would look like a
     *                    signature attack rather than a formatting bug.
     */
    fun verify(orderId: String?, statusCode: String?, grossAmount: String?, providedSignature: String?): Boolean {
        if (!isConfigured() || providedSignature == null
            || orderId == null || statusCode == null || grossAmount == null
        ) {
            return false
        }
        val expected = sha512Hex(orderId + statusCode + grossAmount + serverKey)
        return constantTimeEquals(expected, providedSignature)
    }

    private fun sha512Hex(input: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-512")
            return HexFormat.of().formatHex(digest.digest(input.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: NoSuchAlgorithmException) {
            // SHA-512 is mandated by the JDK spec; if it is genuinely missing, failing loudly
            // is right — silently returning false would disable settlement platform-wide and
            // look like Midtrans sending bad signatures.
            throw IllegalStateException("SHA-512 unavailable", e)
        }
    }

    /**
     * Length-independent, content-independent comparison via
     * [MessageDigest.isEqual], which is documented as timing-attack
     * resistant. `String.equals` short-circuits on the first differing character, which
     * leaks how much of a guessed signature was correct and turns forging one into a
     * character-at-a-time search rather than a 2^512 problem.
     */
    private fun constantTimeEquals(expected: String, provided: String): Boolean =
        MessageDigest.isEqual(
            expected.lowercase().toByteArray(StandardCharsets.UTF_8),
            provided.lowercase().toByteArray(StandardCharsets.UTF_8),
        )

    companion object {
        private const val UNSET = "__unset__"
    }
}
