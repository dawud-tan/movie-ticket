package com.protek.booking.payment.snap

import com.protek.booking.isJavaBlank
import com.protek.booking.payment.MidtransJson
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The three BI-SNAP signature operations, as pure functions.
 *
 * Separated from any gateway or bean deliberately: these are the only part of the migration
 * that can be built and proven before credentials exist, and every one of them fails in the same
 * indistinguishable way when wrong — a bad `stringToSign`, a bad key and a forgery all
 * present as "signature rejected". Making them pure means they can be pinned against Midtrans's
 * own published sample vector rather than only against themselves.
 *
 * **Three shapes, and they are not interchangeable:**
 *  - **Access token** — `clientId + "|" + timestamp`, SHA256withRSA with *our*
 *    private key. Two parts.
 *  - **Transactional** — five colon-separated parts, HMAC-SHA512 with the client secret.
 *  - **Inbound notification** — **four** colon-separated parts, SHA256withRSA verified
 *    with *Midtrans's* public key. It drops the access-token component.
 *
 * Building the notification verifier by copying the transactional formula is the easiest mistake
 * available here: it rejects every genuine notification, and looks exactly like an attack while
 * doing it.
 *
 * **Hash the bytes you actually send.** `minify` is defined as parse-and-re-serialise,
 * so the only safe construction is to build the request body string once, hash that exact string,
 * and transmit that exact string. Serialising twice can produce two different texts and therefore
 * two different digests. This is the same trap `MidtransSignatureVerifier` already records
 * for the legacy API's `gross_amount`.
 *
 * Every function is `@JvmStatic`, so Java (the tests, the resources) still calls
 * `SnapSignatures.minify(…)` as a static method.
 */
object SnapSignatures {

    // ---------------------------------------------------------------- stringToSign builders
    // Exposed rather than inlined because a wrong stringToSign is indistinguishable from a wrong
    // key at the call site, so being able to print the exact string being signed is the only
    // practical way to debug a rejection.

    /** `X-CLIENT-KEY + "|" + X-TIMESTAMP` — note the pipe, not a colon. */
    @JvmStatic
    fun accessTokenStringToSign(clientId: String?, timestamp: String?): String = "$clientId|$timestamp"

    /**
     * `HTTPMethod:EndpointUrl:AccessToken:Lowercase(HexEncode(SHA-256(minify(body)))):Timestamp`
     *
     * @param endpointUrl the **relative path only** — `/v1.0/qr/qr-mpm-generate`, never a
     *                    full URL. Confirmed against Midtrans's Java and Node samples.
     * @param accessToken the bare token, **without** the `Bearer ` prefix that the
     *                    `Authorization` header carries. Midtrans's own sample comments this,
     *                    which is usually a sign it gets got wrong.
     * @param timestamp   must be the identical String placed in the `X-TIMESTAMP` header.
     *                    Midtrans's docs disagree with their samples about the format (offset form
     *                    vs UTC-with-millis); the format is not what matters, byte-identity is.
     *
     * `uppercase()` is locale-independent where Java's `toUpperCase()` used the JVM's default
     * locale — which under a Turkish locale would have turned `options` into `OPTİONS`.
     */
    @JvmStatic
    fun transactionalStringToSign(
        httpMethod: String, endpointUrl: String?, accessToken: String?, requestBody: String?, timestamp: String?,
    ): String = httpMethod.uppercase() + ":" + endpointUrl + ":" + accessToken + ":" +
        bodyHash(requestBody) + ":" + timestamp

    /**
     * `HTTPMethod:EndpointPath:Lowercase(HexEncode(SHA-256(minify(body)))):Timestamp`
     *
     * Four parts. There is no access token in an inbound notification — Midtrans is calling us,
     * so there is no token of ours for it to have used.
     */
    @JvmStatic
    fun notificationStringToSign(
        httpMethod: String, endpointPath: String?, requestBody: String?, timestamp: String?,
    ): String = httpMethod.uppercase() + ":" + endpointPath + ":" + bodyHash(requestBody) + ":" + timestamp

    // ---------------------------------------------------------------- body hashing

    /**
     * `Lowercase(HexEncode(SHA-256(minify(body))))`.
     *
     * An absent body hashes the **empty string** rather than being omitted from the
     * stringToSign or contributing a blank field — so this returns
     * `e3b0c442…b855`, the SHA-256 of no bytes, for a null or blank body.
     */
    @JvmStatic
    fun bodyHash(requestBody: String?): String {
        val minified = minify(requestBody)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(minified.toByteArray(StandardCharsets.UTF_8))
            // HexFormat.of() emits lowercase, which is what SNAP's Lowercase(HexEncode(...)) wants.
            return HexFormat.of().formatHex(digest)
        } catch (e: Exception) {
            throw IllegalStateException("SHA-256 unavailable", e)
        }
    }

    /**
     * Strips insignificant whitespace by parsing and re-serialising — Midtrans's Java sample is
     * literally `new ObjectMapper().readTree(body).toString()`.
     *
     * **That sample is the definition, and since 2026-10-08 it is reproduced, not called.**
     * The parse is kotlinx.serialization's; the rendering is [MidtransJson.render], which writes the
     * tree exactly as Jackson's `toString()` did — `10000.00` as `10000.0`, Jackson's escapes —
     * because a rendering that is merely equivalent JSON changes the digest. `MinifyMatchesJacksonTest`
     * and `SnapNotificationEndpointTest` both compare it against Jackson itself.
     *
     * **Key order is preserved, not sorted.** Document order, which is what makes this
     * interoperable with the Node implementation's `JSON.stringify(JSON.parse(...))`. Sorting keys
     * here would produce a different digest from the same document and reject every signature.
     *
     * Blank is Java's `isBlank`: a body of only a non-breaking space is not blank, as before.
     */
    @JvmStatic
    fun minify(requestBody: String?): String {
        if (requestBody == null || requestBody.isJavaBlank()) {
            return ""
        }
        try {
            return MidtransJson.render(MidtransJson.parse(requestBody))
        } catch (e: Exception) {
            throw IllegalArgumentException("Request body is not valid JSON, so it cannot be " +
                "minified for signing", e)
        }
    }

    // ---------------------------------------------------------------- signing and verifying

    /** SHA256withRSA over [accessTokenStringToSign], base64. Uses our private key. */
    @JvmStatic
    fun signAccessToken(clientId: String?, timestamp: String?, privateKey: PrivateKey?): String =
        rsaSign(accessTokenStringToSign(clientId, timestamp), privateKey)

    /** HMAC-SHA512 over [transactionalStringToSign], keyed with the client secret, base64. */
    @JvmStatic
    fun signTransactional(
        httpMethod: String, endpointUrl: String?, accessToken: String?,
        requestBody: String?, timestamp: String?, clientSecret: String?,
    ): String {
        val stringToSign = transactionalStringToSign(httpMethod, endpointUrl, accessToken, requestBody, timestamp)
        try {
            val mac = Mac.getInstance("HmacSHA512")
            // A null secret fails here, inside the try, as the Java version's NPE did.
            mac.init(SecretKeySpec(clientSecret!!.toByteArray(StandardCharsets.UTF_8), "HmacSHA512"))
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            throw IllegalStateException("HmacSHA512 unavailable or key rejected", e)
        }
    }

    /**
     * Verifies an inbound notification against Midtrans's public key.
     *
     * Returns false rather than throwing for anything an attacker controls — a missing header, a
     * malformed base64 signature, a body that is not JSON. A public endpoint that throws a
     * different exception per failure mode is an oracle, and this is the sole authentication on the
     * write path that confirms seats.
     */
    @JvmStatic
    fun verifyNotification(
        httpMethod: String?, endpointPath: String?, requestBody: String?, timestamp: String?,
        base64Signature: String?, midtransPublicKey: PublicKey?,
    ): Boolean {
        if (httpMethod == null || endpointPath == null || timestamp == null ||
            base64Signature == null || midtransPublicKey == null
        ) {
            return false
        }
        return try {
            val stringToSign = notificationStringToSign(httpMethod, endpointPath, requestBody, timestamp)
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(midtransPublicKey)
            verifier.update(stringToSign.toByteArray(StandardCharsets.UTF_8))
            verifier.verify(Base64.getDecoder().decode(base64Signature))
        } catch (e: Exception) {
            // Malformed signature, unparseable body, bad padding: all "not authenticated".
            false
        }
    }

    private fun rsaSign(stringToSign: String, privateKey: PrivateKey?): String {
        try {
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(stringToSign.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(signer.sign())
        } catch (e: Exception) {
            throw IllegalStateException("SHA256withRSA signing failed", e)
        }
    }

    // ---------------------------------------------------------------- PEM loading

    /**
     * Loads our PKCS8 private key.
     *
     * PKCS8 specifically: the PEM must say `BEGIN PRIVATE KEY`, not
     * `BEGIN RSA PRIVATE KEY` — the latter is PKCS1, which `openssl genrsa` produces
     * and [PKCS8EncodedKeySpec] cannot read. Midtrans asks for PKCS8 and
     * `openssl genpkey` emits it.
     */
    @JvmStatic
    fun privateKeyFromPem(pem: String?): PrivateKey {
        if (pem != null && pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw IllegalArgumentException("Private key is PKCS1 (BEGIN RSA PRIVATE KEY); " +
                "BI-SNAP requires PKCS8 (BEGIN PRIVATE KEY). Convert it with " +
                "'openssl pkcs8 -topk8 -nocrypt' or regenerate with 'openssl genpkey'.")
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decodePemBody(pem)))
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not read PKCS8 private key", e)
        }
    }

    /** Loads Midtrans's public key, an X.509 `SubjectPublicKeyInfo` PEM. */
    @JvmStatic
    fun publicKeyFromPem(pem: String?): PublicKey {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decodePemBody(pem)))
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not read public key", e)
        }
    }

    /**
     * Strips the PEM armour and decodes.
     *
     * Tolerates a key supplied as one long line with no header at all, because that is how these
     * values arrive through a systemd `EnvironmentFile`, which
     * has no multi-line value syntax, so a PEM passed as an env var loses its newlines.
     */
    private fun decodePemBody(pem: String?): ByteArray {
        if (pem == null || pem.isJavaBlank()) {
            throw IllegalArgumentException("Key is empty")
        }
        val body = pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
        return Base64.getDecoder().decode(body)
    }
}
