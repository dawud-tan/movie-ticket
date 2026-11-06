package com.protek.booking.payment.snap

import com.protek.booking.isJavaBlank
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Everything the BI-SNAP credential exchange produces, loaded and validated once at startup.
 *
 * **Keys arrive as file paths, not as values.** The alternative — the PEM itself in an
 * environment variable — puts a signing key into `systemctl show`, `/proc/<pid>/environ`
 * and every crash dump, and makes rotation an edit of the env file rather than a file replacement.
 * A path leaves the protecting to file permissions, which is the same choice
 * `GOOGLE_APPLICATION_CREDENTIALS` already makes. Two consequences of that
 * precedent apply verbatim: systemd's `EnvironmentFile` performs **no shell expansion**, so
 * a `~` or `$HOME` in the value is passed through literally and fails; and the file
 * must be readable by the unit's `User=` account, since a key under another account's mode-700
 * home reads back as file-not-found rather than as a permission error.
 *
 * **Two keys, and they are not a pair.** The private key is *ours* and signs the
 * access-token request. The public key is *Midtrans's* and verifies inbound notifications. Our
 * own public key is an onboarding artifact — it is submitted during the exchange and never read at
 * runtime, being re-derivable from the private key at any time.
 *
 * **Why this validates eagerly.** It is modelled on a Web Push failure that was actually hit:
 * `FirebaseApp` initialises lazily on first send, push is fire-and-forget after commit, so
 * bad credentials broke nothing at boot, broke no booking, and had no external probe — which is
 * exactly why it stayed invisible. A signing key is worse: the first symptom of a missing one would
 * be a settlement that never applies. [Startup] forces the check to happen while someone is
 * still watching the log.
 */
@Startup
@ApplicationScoped
class SnapCredentials {

    // Public, nullable fields: the tests build one by assigning these and calling load().
    @ConfigProperty(name = "midtrans.snap.client-id")
    @JvmField
    final var clientId: String? = null

    @ConfigProperty(name = "midtrans.snap.client-secret")
    @JvmField
    final var clientSecret: String? = null

    @ConfigProperty(name = "midtrans.snap.partner-id")
    @JvmField
    final var partnerId: String? = null

    @ConfigProperty(name = "midtrans.snap.channel-id")
    @JvmField
    final var channelId: String? = null

    @ConfigProperty(name = "midtrans.snap.private-key-path")
    @JvmField
    final var privateKeyPath: String? = null

    @ConfigProperty(name = "midtrans.snap.midtrans-public-key-path")
    @JvmField
    final var midtransPublicKeyPath: String? = null

    private var privateKey: PrivateKey? = null
    private var midtransPublicKey: PublicKey? = null
    private var configured = false

    @PostConstruct
    fun load() {
        val missing = mutableListOf<String>()
        check(clientId, "midtrans.snap.client-id", missing)
        check(clientSecret, "midtrans.snap.client-secret", missing)
        check(partnerId, "midtrans.snap.partner-id", missing)
        check(channelId, "midtrans.snap.channel-id", missing)
        check(privateKeyPath, "midtrans.snap.private-key-path", missing)
        check(midtransPublicKeyPath, "midtrans.snap.midtrans-public-key-path", missing)

        if (missing.size == PROPERTY_COUNT) {
            // The ordinary state until the credential exchange completes. BI-SNAP is simply off;
            // the legacy integration keeps working and a booking still falls back to pay-at-counter.
            LOG.info("BI-SNAP is not configured; leaving it disabled")
            configured = false
            return
        }
        if (missing.isNotEmpty()) {
            // Deliberately fatal rather than "treat as disabled". The Web Push outage was exactly
            // this shape: VAPID_PUBLIC_KEY set without GOOGLE_APPLICATION_CREDENTIALS gave clients
            // that subscribed successfully and then received nothing, with no probe that could tell
            // you. A half-configured payment credential deserves less patience, not more.
            throw IllegalStateException("BI-SNAP is partially configured, which is never " +
                "intentional: set all " + PROPERTY_COUNT + " properties or none. Missing: " +
                missing +
                ". If the missing one is partner-id and the dashboard's access-keys page " +
                "never showed you a Partner ID, it has not been issued yet rather than being " +
                "unnecessary — X-PARTNER-ID is a mandatory header on every transactional " +
                "call.")
        }

        // Both paths were checked present above, so neither is null here.
        privateKey = SnapSignatures.privateKeyFromPem(read(privateKeyPath!!, "our PKCS8 private key"))
        midtransPublicKey = SnapSignatures.publicKeyFromPem(
            read(midtransPublicKeyPath!!, "Midtrans's public key"))
        configured = true
        LOG.infof("BI-SNAP credentials loaded for partner %s", partnerId)
    }

    /** False until the credential exchange completes. Callers fall back rather than fail. */
    fun isConfigured(): Boolean = configured

    fun clientId(): String = requireValue(clientId)

    fun clientSecret(): String = requireValue(clientSecret)

    /** `X-PARTNER-ID`, mandatory on every transactional call — but **not** on the
     * access-token call, which needs only the client id, the secret and our private key. That
     * split is why an unissued Partner ID does not block validating the rest. */
    fun partnerId(): String = requireValue(partnerId)

    /** `CHANNEL-ID` — note the header carries no `X-` prefix, unlike its neighbours. */
    fun channelId(): String = requireValue(channelId)

    /** Ours — signs the access-token request. */
    fun privateKey(): PrivateKey {
        requireConfigured()
        return privateKey!!
    }

    /** Midtrans's — verifies inbound notification signatures. */
    fun midtransPublicKey(): PublicKey {
        requireConfigured()
        return midtransPublicKey!!
    }

    private fun requireValue(value: String?): String {
        requireConfigured()
        // load() only sets configured once every value has passed check(), so none is null here.
        return value!!
    }

    /** An explicit failure rather than handing back a null that becomes an NPE several frames
     * later, inside crypto, where it reads as a key problem rather than a config one. */
    private fun requireConfigured() {
        if (!configured) {
            throw IllegalStateException("BI-SNAP credentials are not configured; " +
                "callers must check isConfigured() first")
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(SnapCredentials::class.java)

        /** An empty default (`${VAR:}`) throws ConfigurationException at startup when the
         * variable is absent, so every one of these carries a non-empty sentinel instead. */
        private const val UNSET = "__unset__"

        /** Kept next to the checks in [load]; adding a property and forgetting this constant
         * would turn "nothing configured" into a startup failure for every existing deployment. */
        private const val PROPERTY_COUNT = 6

        private fun check(value: String?, property: String, missing: MutableList<String>) {
            if (value == null || value.isJavaBlank() || UNSET == value) {
                // Reported as "property (ENV_VAR)" because the two audiences differ and only one of
                // them is reading Java: whoever hits this is editing the env file, where
                // the property name alone is not what they need to type. Quarkus's mapping is
                // mechanical but not everyone carries it in their head.
                missing.add(property + " (" + asEnvVar(property) + ")")
            }
        }

        /** Quarkus's property-to-environment mapping: non-alphanumerics become underscores,
         * uppercased (in the root locale, which Java's default-locale `toUpperCase()` was not). */
        private fun asEnvVar(property: String): String =
            property.replace(Regex("[^A-Za-z0-9]"), "_").uppercase()

        /**
         * Reads a PEM, turning the two failures that actually happen into messages that name their own
         * fix. A missing file and an unreadable one are reported together on purpose: a key under
         * another account's mode-700 home surfaces as file-not-found, so "check permissions" belongs in
         * the same breath as "check the path".
         */
        private fun read(path: String, what: String): String {
            if (path.startsWith("~") || path.contains('$')) {
                throw IllegalStateException("Path to " + what + " is '" + path + "', but systemd's " +
                    "EnvironmentFile does no shell expansion — use an absolute path")
            }
            try {
                return Files.readString(Path.of(path))
            } catch (e: IOException) {
                throw IllegalStateException("Could not read " + what + " at '" + path +
                    "'. Check the path is absolute and that the service user can read the file — " +
                    "a file under another account's mode-700 home reports as not-found", e)
            }
        }
    }
}
