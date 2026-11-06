package com.protek.booking.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Arrays
import java.util.Base64
import java.util.UUID

/**
 * A stand-in for Google's OpenID provider, so that a `@QuarkusIntegrationTest` can sign in
 * to the packaged (native) binary. `@TestSecurity` does not reach a separate process, and
 * a test-only login would have to be compiled into the very image that ships — so the binary is
 * left exactly as it is and only pointed, through the config this class returns, at a provider
 * the test controls. Everything after that is the real path: the code flow, ID-token signature
 * and audience checks, and `AppUserAugmentor` mapping the `email` claim to a role.
 *
 * JDK only (`HttpServer`, RSA `Signature`), not WireMock or
 * `quarkus-test-oidc-server`: the latter's code flow always answers as one fixed user,
 * and these tests need a staff member and several distinct customers.
 *
 * The authorization code *is* the email address to sign in as: there is no browser
 * and no consent screen, so [NativeItSupport.signIn] reads `state` off the app's
 * redirect and calls `/callback` itself, and `/token` turns the code back into an
 * ID token for that address. Who a test signs in as therefore decides its role exactly as it
 * would in production: `staff@demo-cinema.example` is the seeded `tenant_staff`
 * row, and any other address is auto-provisioned as a customer.
 *
 * It also turns on the `customer-app` bearer tenant on `/api/app`, with the same client id
 * as its audience, as production pins it to `OIDC_CLIENT_ID`. A test gets a bearer token the way
 * the phone does, from `/token`, whose optional `audience` parameter mints one for another
 * client — the token a bearer tenant must refuse (`AppApiSignedInTest`).
 *
 * Apply it with `@WithTestResource`, which restricts it to the annotated class.
 * `@QuarkusTestResource` would apply it to every `@QuarkusTest` in the suite too, and turn on OIDC for all of them.
 *
 * The JSON bodies were Java text blocks joined with `\` line continuations, so each is one
 * raw string here with the identical value; `$$` because `%1$s` would otherwise interpolate.
 */
class FakeGoogleOidc : QuarkusTestResourceLifecycleManager {

    companion object {
        internal const val CLIENT_ID = "booking-native-it"

        // Kotlin's split("&") keeps a trailing empty string where Java's regex split dropped it;
        // an empty pair has no '=' and is skipped by the eq > 0 test either way.
        private fun formParam(body: String, name: String): String? {
            for (pair in body.split("&")) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq) == name) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                }
            }
            return null
        }

        private fun json(ex: HttpExchange, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }

        /** A JWK's `n` and `e` are unsigned big-endian; BigInteger may prepend a sign byte. */
        private fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes[0].toInt() == 0) Arrays.copyOfRange(bytes, 1, bytes.size) else bytes
        }

        private fun b64(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private var server: HttpServer? = null
    private lateinit var keys: KeyPair
    private lateinit var issuer: String

    override fun start(): Map<String, String> {
        val server: HttpServer
        try {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            keys = generator.generateKeyPair()
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        } catch (e: Exception) {
            throw IllegalStateException("fake OIDC provider could not start", e)
        }
        this.server = server
        issuer = "http://127.0.0.1:" + server.address.port

        server.createContext("/.well-known/openid-configuration") { ex -> json(ex, $$"""{"issuer":"%1$s","authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token","jwks_uri":"%1$s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"]}"""
            .format(issuer)) }
        server.createContext("/jwks") { ex ->
            val pub = keys.public as RSAPublicKey
            json(ex, """{"keys":[{"kty":"RSA","kid":"it","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}"""
                .format(b64(unsigned(pub.modulus)), b64(unsigned(pub.publicExponent))))
        }
        server.createContext("/token") { ex ->
            val body = String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            val email = formParam(body, "code")
            if (email == null) {
                ex.sendResponseHeaders(400, -1)
                ex.close()
                return@createContext
            }
            json(ex, """{"access_token":"%s","token_type":"Bearer","expires_in":3600,"id_token":"%s"}"""
                .format(UUID.randomUUID(), idToken(email, formParam(body, "audience") ?: CLIENT_ID)))
        }
        server.start()

        // The same keys application.properties reads from OIDC_* env vars. Unprofiled keys, so
        // these system properties outrank the file under the binary's prod profile — unlike the
        // datasource credentials, which are %prod-prefixed.
        val config = HashMap<String, String>()
        config["quarkus.oidc.tenant-enabled"] = "true"
        config["quarkus.oidc.auth-server-url"] = issuer
        config["quarkus.oidc.client-id"] = CLIENT_ID
        config["quarkus.oidc.credentials.secret"] = "native-it-secret"
        config["quarkus.oidc.customer-app.tenant-enabled"] = "true"
        config["quarkus.oidc.customer-app.auth-server-url"] = issuer
        config["quarkus.oidc.customer-app.client-id"] = CLIENT_ID
        config["quarkus.oidc.customer-app.token.audience"] = CLIENT_ID
        return config
    }

    override fun stop() {
        server?.stop(0)
    }

    private fun idToken(email: String, audience: String): String {
        val now = Instant.now().epochSecond
        val header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"it\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = b64("""{"iss":"%s","aud":"%s","sub":"it-%s","email":"%s","email_verified":true,"name":"%s","iat":%d,"exp":%d}""".format(issuer, audience, email, email, email, now, now + 3600)
            .toByteArray(StandardCharsets.UTF_8))
        try {
            val rs256 = Signature.getInstance("SHA256withRSA")
            rs256.initSign(keys.private)
            rs256.update((header + "." + claims).toByteArray(StandardCharsets.US_ASCII))
            return header + "." + claims + "." + b64(rs256.sign())
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }
}
