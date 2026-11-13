package com.protek.booking.payment.snap

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Pins how BI-SNAP credentials load, and — more to the point — how they refuse to.
 *
 * Plain JUnit against a directly-constructed bean rather than `@QuarkusTest`: the
 * behaviour worth testing is what `load()` does with each combination of settings, and
 * driving that through real configuration would mean one Quarkus boot per case for no extra
 * coverage. The fields are public for exactly this reason, matching
 * `MidtransSignatureVerifier`.
 */
class SnapCredentialsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun withNothingConfigured_itIsSimplyDisabled_ratherThanFailing() {
        val credentials = unconfigured()
        assertDoesNotThrow(credentials::load,
            "an unconfigured BI-SNAP is the ordinary state until the credential exchange " +
                "completes, and must not stop the application booting")
        assertFalse(credentials.isConfigured())
    }

    @Test
    fun withEverythingConfigured_theKeysLoadAndActuallyWork() {
        val ours = rsaKeyPair()
        val midtrans = rsaKeyPair()
        val credentials = configured(
            writePem(ours.private.encoded, "PRIVATE KEY", "our-private-key.pem"),
            writePem(midtrans.public.encoded, "PUBLIC KEY", "midtrans-public-key.pem"))

        credentials.load()

        assertTrue(credentials.isConfigured())
        assertEquals("CLIENT-1", credentials.clientId())
        assertEquals("PARTNER-1", credentials.partnerId())
        // Loading is not the property that matters — being able to sign and verify with what was
        // loaded is. A key that parses but is the wrong one would pass a mere non-null assertion.
        val signature = SnapSignatures.signAccessToken("CLIENT-1", "2026-08-15T08:16:38+07:00",
            credentials.privateKey())
        assertEquals(ours.private, credentials.privateKey())
        assertNotNull(signature)
        assertEquals(midtrans.public, credentials.midtransPublicKey())
    }

    @Test
    fun aPartiallyConfiguredIntegration_failsAtStartup_ratherThanLookingDisabled() {
        // The Web Push outage's shape: VAPID_PUBLIC_KEY set without GOOGLE_APPLICATION_CREDENTIALS gave
        // clients that subscribed fine and then received nothing, with no probe to reveal it.
        // Treating a half-set payment credential as "disabled" would reproduce that exactly.
        val credentials = configured(
            writePem(rsaKeyPair().private.encoded, "PRIVATE KEY", "k.pem"),
            writePem(rsaKeyPair().public.encoded, "PUBLIC KEY", "m.pem"))
        credentials.clientSecret = "__unset__"

        val e = assertThrows(IllegalStateException::class.java, credentials::load)
        assertTrue(e.message!!.contains("midtrans.snap.client-secret"),
            "the message must name what is missing, or the operator has to bisect six " +
                "properties by hand: " + e.message)
        // And the environment variable, because whoever reads this failure is editing
        // the environment file, where the property name is not what they need to type. This
        // is not hypothetical: a credential once arrived as a bare MIDTRANS_CLIENT_SECRET, which
        // nothing reads, and the prefix is the only thing separating the two credential
        // generations.
        assertTrue(e.message!!.contains("MIDTRANS_SNAP_CLIENT_SECRET"), e.message)
        assertFalse(credentials.isConfigured())
    }

    @Test
    fun anAbsentPartnerId_isStillFatal_becauseTheHeaderIsMandatory() {
        // Tempting to relax, because Midtrans's dashboard may show no Partner ID for a single
        // merchant account and their docs contradict each other about whether one is issued.
        // Relaxing it would not make X-PARTNER-ID optional in the protocol though — it is
        // mandatory on every transactional call — it would only move the failure from boot to the
        // first charge, where it arrives as an auth error indistinguishable from a bad signature.
        val credentials = configured(
            writePem(rsaKeyPair().private.encoded, "PRIVATE KEY", "k.pem"),
            writePem(rsaKeyPair().public.encoded, "PUBLIC KEY", "m.pem"))
        credentials.partnerId = "__unset__"

        val e = assertThrows(IllegalStateException::class.java, credentials::load)
        assertTrue(e.message!!.contains("midtrans.snap.partner-id"), e.message)
        assertTrue(e.message!!.contains("not been issued yet"),
            "the message has to say what to do about it, since 'my dashboard has no such " +
                "field' is the expected reaction: " + e.message)
    }

    @Test
    fun channelIdIsRequiredToo_sinceItIsAlsoAMandatoryHeader() {
        val credentials = configured(
            writePem(rsaKeyPair().private.encoded, "PRIVATE KEY", "k.pem"),
            writePem(rsaKeyPair().public.encoded, "PUBLIC KEY", "m.pem"))
        credentials.channelId = "__unset__"

        val e = assertThrows(IllegalStateException::class.java, credentials::load)
        assertTrue(e.message!!.contains("midtrans.snap.channel-id"), e.message)
    }

    @Test
    fun aMissingKeyFile_namesThePathAndThePermissionTrap() {
        val credentials = configured(dir.resolve("absent.pem").toString(),
            dir.resolve("also-absent.pem").toString())

        val e = assertThrows(IllegalStateException::class.java, credentials::load)
        assertTrue(e.message!!.contains("absent.pem"), e.message)
        // A key under another account's mode-700 home reports as not-found, not as denied,
        // so the path and the permission hint have to arrive together.
        assertTrue(e.message!!.contains("read the file"), e.message)
    }

    @Test
    fun aTildeInThePath_isRejectedWithTheReasonRatherThanAsNotFound() {
        // systemd's EnvironmentFile does no shell expansion, so "~/.secrets/k.pem" reaches us
        // literally. Left to Files.readString this surfaces as a confusing not-found for a path
        // the operator can see exists in their own shell.
        val credentials = configured("~/.secrets/midtrans-snap/sandbox/private-key.pem",
            dir.resolve("m.pem").toString())

        val e = assertThrows(IllegalStateException::class.java, credentials::load)
        assertTrue(e.message!!.contains("no shell expansion"), e.message)
    }

    @Test
    fun aPkcs1PrivateKey_isRejectedWithTheConversionCommand() {
        // openssl genrsa produces PKCS1 and the onboarding doc calls for PKCS8; this is the most
        // likely way to get the key file wrong while still having a real key in it.
        val pkcs1 = dir.resolve("pkcs1.pem")
        Files.writeString(pkcs1, "-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----")
        val credentials = configured(pkcs1.toString(),
            writePem(rsaKeyPair().public.encoded, "PUBLIC KEY", "m.pem"))

        val e = assertThrows(IllegalArgumentException::class.java, credentials::load)
        assertTrue(e.message!!.contains("PKCS8"), e.message)
    }

    @Test
    fun readingCredentialsBeforeTheyAreConfigured_throwsInsteadOfReturningNull() {
        val credentials = unconfigured()
        credentials.load()

        // A null private key would surface several frames later inside Signature.initSign, where
        // it reads as a key problem rather than a configuration one.
        assertThrows(IllegalStateException::class.java) { credentials.privateKey() }
        assertThrows(IllegalStateException::class.java) { credentials.clientSecret() }
        assertThrows(IllegalStateException::class.java) { credentials.midtransPublicKey() }
    }

    // ------------------------------------------------------------------ helpers

    private fun unconfigured(): SnapCredentials {
        val credentials = SnapCredentials()
        credentials.clientId = "__unset__"
        credentials.clientSecret = "__unset__"
        credentials.partnerId = "__unset__"
        credentials.channelId = "__unset__"
        credentials.privateKeyPath = "__unset__"
        credentials.midtransPublicKeyPath = "__unset__"
        return credentials
    }

    private fun configured(privateKeyPath: String, midtransPublicKeyPath: String): SnapCredentials {
        val credentials = SnapCredentials()
        credentials.clientId = "CLIENT-1"
        credentials.clientSecret = "SECRET-1"
        credentials.partnerId = "PARTNER-1"
        credentials.channelId = "CHANNEL-1"
        credentials.privateKeyPath = privateKeyPath
        credentials.midtransPublicKeyPath = midtransPublicKeyPath
        return credentials
    }

    private fun writePem(der: ByteArray, label: String, name: String): String {
        val file = dir.resolve(name)
        Files.writeString(file, "-----BEGIN " + label + "-----\n" +
            Base64.getMimeEncoder().encodeToString(der) +
            "\n-----END " + label + "-----\n")
        return file.toString()
    }

    companion object {
        private fun rsaKeyPair(): KeyPair {
            try {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                return generator.generateKeyPair()
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }
    }
}
