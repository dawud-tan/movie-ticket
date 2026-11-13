package com.protek.booking.payment.snap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Pins the BI-SNAP signature scheme.
 *
 * Plain JUnit, no `@QuarkusTest`: these are pure functions with no CDI, no database and no
 * configuration, so booting Quarkus would add seconds per run and prove nothing extra.
 *
 * **The first test is the one that matters.** Everything else here is self-consistency —
 * this codebase signing something and then agreeing with itself, which an implementation that is
 * wrong in the same way twice would also pass. The published-vector test is the only one that
 * checks agreement with *Midtrans*, and it covers every component at once: get the minify
 * rule, the hash encoding, the field order, the separator, the HMAC or the base64 wrong by one
 * character and it fails.
 */
class SnapSignaturesTest {

    companion object {
        /*
         * The worked example published in Midtrans's own SNAP-BI-Signature-Demo, with the timestamp
         * their sample hardcodes. Verified reproducible before being pinned here — the sample output
         * really is the signature of these exact inputs, which is what makes it usable as a
         * known-answer test rather than decoration.
         */
        private const val SAMPLE_CLIENT_SECRET = "myclientsecret"
        private const val SAMPLE_ACCESS_TOKEN = "myaccesstoken"
        private const val SAMPLE_ENDPOINT = "/v1.0/qr/qr-mpm-generate"
        private const val SAMPLE_TIMESTAMP = "2024-03-05T10:37:16.196Z"
        private const val SAMPLE_EXPECTED_SIGNATURE =
            "wmzT7yjg1SxxCsxNsFgCR9SYXiZGGbceF6fV8xe97sAMHxZ+7OEBMIBmvb6Sj6GcksGp3iv77DiomgSbcST3XQ=="

        /** Pretty-printed exactly as their sample is, so the minify step is genuinely exercised. */
        private val SAMPLE_BODY = """
            {
              "partnerReferenceNo": "2020102900000000000001",
              "amount": {
                "value": "12345678.00",
                "currency": "IDR"
              },
              "feeAmount": {
                "value": "12345678.00",
                "currency": "IDR"
              },
              "merchantId": "00007100010926",
              "terminalId": "213141251124",
              "validityPeriod": "2009-07-03T12:08:56-07:00"
            }""".trimIndent()

        /** SHA-256 of no bytes — what an absent request body contributes. */
        private const val SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private fun rsaKeyPair(): KeyPair {
            try {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                return generator.generateKeyPair()
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun rsaSign(stringToSign: String, key: PrivateKey): String {
            try {
                val signer = Signature.getInstance("SHA256withRSA")
                signer.initSign(key)
                signer.update(stringToSign.toByteArray(StandardCharsets.UTF_8))
                return Base64.getEncoder().encodeToString(signer.sign())
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun rsaVerify(stringToSign: String, base64Signature: String, key: PublicKey): Boolean {
            try {
                val verifier = Signature.getInstance("SHA256withRSA")
                verifier.initVerify(key)
                verifier.update(stringToSign.toByteArray(StandardCharsets.UTF_8))
                return verifier.verify(Base64.getDecoder().decode(base64Signature))
            } catch (e: Exception) {
                return false
            }
        }
    }

    @Test
    fun transactionalSignature_reproducesMidtransOwnPublishedSample() {
        assertEquals(SAMPLE_EXPECTED_SIGNATURE,
            SnapSignatures.signTransactional("POST", SAMPLE_ENDPOINT, SAMPLE_ACCESS_TOKEN,
                SAMPLE_BODY, SAMPLE_TIMESTAMP, SAMPLE_CLIENT_SECRET),
            "this is the only check here that tests agreement with Midtrans rather than with " +
                "ourselves — if it fails, the integration will be rejected in production " +
                "with an error indistinguishable from a wrong client secret")
    }

    @Test
    fun absentBody_hashesTheEmptyString_ratherThanBeingOmitted() {
        // A GET, or any request with no body, still contributes a hash component. Leaving it out
        // or leaving it blank produces a stringToSign with the wrong number of parts.
        assertEquals(SHA256_OF_EMPTY, SnapSignatures.bodyHash(null))
        assertEquals(SHA256_OF_EMPTY, SnapSignatures.bodyHash(""))
        assertEquals(SHA256_OF_EMPTY, SnapSignatures.bodyHash("   "))
    }

    @Test
    fun minify_stripsWhitespaceButKeepsKeyOrder() {
        val pretty = "{\n  \"b\": 1,\n  \"a\": {\n    \"z\": \"x\"\n  }\n}"
        assertEquals("{\"b\":1,\"a\":{\"z\":\"x\"}}", SnapSignatures.minify(pretty),
            "document order must survive: sorting keys would produce a different digest from " +
                "the same document and reject every signature, and Midtrans's Node " +
                "implementation (JSON.stringify(JSON.parse(...))) does not sort")
    }

    @Test
    fun formattingOfTheBodyDoesNotChangeTheSignature_butItsContentDoes() {
        val compact = "{\"partnerReferenceNo\":\"A1\",\"merchantId\":\"M1\"}"
        val pretty = "{\n   \"partnerReferenceNo\" : \"A1\",\n   \"merchantId\" : \"M1\"\n}"
        assertEquals(SnapSignatures.bodyHash(compact), SnapSignatures.bodyHash(pretty),
            "whitespace is insignificant, which is the entire point of the minify step")
        assertNotEquals(SnapSignatures.bodyHash(compact),
            SnapSignatures.bodyHash("{\"partnerReferenceNo\":\"A2\",\"merchantId\":\"M1\"}"),
            "a changed amount or reference must change the digest, or the signature protects " +
                "nothing")
    }

    @Test
    fun accessTokenSignature_verifiesWithTheMatchingPublicKey_andIsBoundToItsTimestamp() {
        val ours = rsaKeyPair()
        val timestamp = "2026-08-15T08:16:38+07:00"
        val signature = SnapSignatures.signAccessToken("CLIENT-1", timestamp, ours.private)

        assertTrue(rsaVerify(SnapSignatures.accessTokenStringToSign("CLIENT-1", timestamp),
            signature, ours.public))
        // Replaying yesterday's signature against today's timestamp must fail, or X-TIMESTAMP is
        // decorative and a captured header is reusable forever.
        assertFalse(rsaVerify(SnapSignatures.accessTokenStringToSign("CLIENT-1", "2026-08-16T08:16:38+07:00"),
            signature, ours.public))
    }

    @Test
    fun accessTokenStringToSign_usesAPipe_notAColon() {
        // The one place in the whole scheme that is not colon-separated. Copying the separator from
        // the transactional formula yields a signature Midtrans rejects at the very first call,
        // before anything else can be tested.
        assertEquals("CLIENT-1|2026-08-15T08:16:38+07:00",
            SnapSignatures.accessTokenStringToSign("CLIENT-1", "2026-08-15T08:16:38+07:00"))
    }

    @Test
    fun notificationSignature_hasFourParts_soATransactionalSignatureDoesNotVerifyAsOne() {
        val midtrans = rsaKeyPair()
        val path = "/v1.0/qr/qr-mpm-notify"
        val body = "{\"originalPartnerReferenceNo\":\"BP-1\",\"latestTransactionStatus\":\"00\"}"
        val timestamp = "2026-08-15T08:16:38+07:00"

        val correct = SnapSignatures.notificationStringToSign("POST", path, body, timestamp)
        // Stated as an exact string rather than by counting colons: the timestamp contains three
        // of its own, so any count is arithmetic about the wrong thing.
        assertEquals("POST:" + path + ":" + SnapSignatures.bodyHash(body) + ":" + timestamp, correct,
            "method, path, body hash, timestamp — and no access-token field between the path " +
                "and the hash, which is the whole difference from the transactional form")

        val signedCorrectly = rsaSign(correct, midtrans.private)
        assertTrue(SnapSignatures.verifyNotification("POST", path, body, timestamp,
            signedCorrectly, midtrans.public))

        // The trap this test exists for: the transactional formula inserts an access-token field.
        // A verifier built by copying it rejects every genuine notification, failing exactly the
        // way a forgery would — so nothing in the logs would say "you used the wrong formula".
        val signedWithTheFivePartForm = rsaSign(
            SnapSignatures.transactionalStringToSign("POST", path, "some-token", body, timestamp),
            midtrans.private)
        assertFalse(SnapSignatures.verifyNotification("POST", path, body, timestamp,
            signedWithTheFivePartForm, midtrans.public),
            "the five-part transactional form must not verify as a notification")
    }

    @Test
    fun notificationVerification_returnsFalse_ratherThanThrowing_forAnythingAnAttackerControls() {
        val midtrans = rsaKeyPair()
        val path = "/v1.0/qr/qr-mpm-notify"
        val timestamp = "2026-08-15T08:16:38+07:00"

        // A public endpoint that throws a distinct exception per failure mode tells an attacker
        // which part of their forgery was wrong.
        assertFalse(SnapSignatures.verifyNotification("POST", path, "{}", timestamp,
            "not-valid-base64!!", midtrans.public))
        assertFalse(SnapSignatures.verifyNotification("POST", path, "{}", timestamp,
            null, midtrans.public))
        assertFalse(SnapSignatures.verifyNotification("POST", path, "{}", timestamp,
            Base64.getEncoder().encodeToString(ByteArray(256)), midtrans.public))
    }

    @Test
    fun accessToken_mustNotCarryItsBearerPrefixIntoTheSignature() {
        val withPrefix = SnapSignatures.signTransactional("POST", SAMPLE_ENDPOINT,
            "Bearer " + SAMPLE_ACCESS_TOKEN, SAMPLE_BODY, SAMPLE_TIMESTAMP, SAMPLE_CLIENT_SECRET)
        assertNotEquals(SAMPLE_EXPECTED_SIGNATURE, withPrefix,
            "the Authorization header carries 'Bearer <token>' but the stringToSign takes the " +
                "bare token — Midtrans's own sample comments this, which is usually a " +
                "sign it is commonly got wrong")
    }

    @Test
    fun httpMethodIsUppercasedForSigning_becauseTheSpecSaysUppercase() {
        assertEquals(
            SnapSignatures.transactionalStringToSign("POST", SAMPLE_ENDPOINT, "t", "{}", "ts"),
            SnapSignatures.transactionalStringToSign("post", SAMPLE_ENDPOINT, "t", "{}", "ts"))
    }

    @Test
    fun aPkcs1PrivateKey_isRejectedWithAnActionableMessage() {
        // openssl genrsa produces PKCS1, and PKCS8EncodedKeySpec cannot read it. Without this the
        // failure is an InvalidKeySpecException with no hint about which of the two formats is
        // wanted.
        val e = assertThrows(IllegalArgumentException::class.java) {
            SnapSignatures.privateKeyFromPem(
                "-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----")
        }
        assertTrue(e.message!!.contains("PKCS8"), "actual: " + e.message)
    }

    @Test
    fun aPemStrippedOfItsNewlines_stillLoads() {
        // systemd's EnvironmentFile has no multi-line value syntax, so a PEM delivered through
        // an environment file arrives as one long line with the armour intact but no breaks.
        val pair = rsaKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----" +
            Base64.getMimeEncoder().encodeToString(pair.public.encoded) +
            "-----END PUBLIC KEY-----"
        assertEquals(pair.public, SnapSignatures.publicKeyFromPem(pem.replace("\n", "")))
    }
}
