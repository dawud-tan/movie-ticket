package com.protek.booking.payment.snap

import com.protek.booking.payment.MidtransJson
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fetches and caches the BI-SNAP B2B access token.
 *
 * Its own class rather than a method on the gateway because the token has a lifetime and the
 * charge does not: every transactional call needs a valid token, and refetching one per call would
 * double the round trips on the path a customer is waiting on.
 *
 * **Measured, not assumed:** the sandbox probe returned `expiresIn` 900 and a JWT whose
 * own `exp - iat` was exactly 900, so a token is good for 15 minutes. It is refreshed
 * [REFRESH_MARGIN_SECONDS] (60) seconds early — a token that expires between our check and
 * Midtrans reading it comes back as response code 4014701, which is indistinguishable from a signing
 * error at the call site and would send someone hunting the wrong bug.
 *
 * Note this call carries **no** `X-PARTNER-ID` and no `CHANNEL-ID`; it needs only
 * the client id, the private key and a timestamp. That is what made it usable as an isolated probe
 * of whether the submitted public key was stored in a usable form.
 */
@ApplicationScoped
class SnapAccessTokenProvider {

    @Inject
    lateinit var credentials: SnapCredentials

    @ConfigProperty(name = "midtrans.snap.base-url")
    @JvmField
    final var baseUrl: String? = null

    @ConfigProperty(name = "midtrans.snap.http-timeout-seconds", defaultValue = "15")
    @JvmField
    final var timeoutSeconds: Int = 15

    private lateinit var httpClient: HttpClient

    /** Guarded by `this`; read on the fast path without locking. */
    @Volatile
    private var cached: Cached? = null

    private class Cached(val token: String, val refreshAfter: Instant)

    @PostConstruct
    fun init() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    }

    /**
     * A usable token, from cache when one is still good.
     *
     * Synchronised on the miss path only. Under a burst of concurrent charges the first caller
     * fetches and the rest wait rather than each firing their own token request — which would not
     * be merely wasteful: several near-simultaneous token calls are exactly the shape that trips
     * rate limiting on someone else's infrastructure.
     */
    fun token(): String {
        val snapshot = cached
        if (snapshot != null && now().isBefore(snapshot.refreshAfter)) {
            return snapshot.token
        }
        synchronized(this) {
            // Re-check: another thread may have refreshed while this one waited.
            val again = cached
            if (again != null && now().isBefore(again.refreshAfter)) {
                return again.token
            }
            val fresh = fetch()
            cached = fresh
            return fresh.token
        }
    }

    /** Drops the cached token, so the next call fetches. For a 401 that survives a retry. */
    @Synchronized
    fun invalidate() {
        cached = null
    }

    private fun fetch(): Cached {
        val timestamp = timestamp()
        val signature = SnapSignatures.signAccessToken(credentials.clientId(), timestamp, credentials.privateKey())

        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + PATH))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .header("X-TIMESTAMP", timestamp)
            .header("X-CLIENT-KEY", credentials.clientId())
            .header("X-SIGNATURE", signature)
            .POST(HttpRequest.BodyPublishers.ofString("{\"grantType\":\"client_credentials\"}", StandardCharsets.UTF_8))
            .build()

        val body = parse(exchange(request))
        val responseCode = MidtransJson.text(body, "responseCode")
        val token = MidtransJson.text(body, "accessToken")
        if ("2007300" != responseCode || token == null) {
            // Named explicitly, because every failure in this integration reads the same at the
            // call site. 4017300/4017301 is the signature or the key; 4007302 is a malformed
            // request. The message carries Midtrans's own code so the docs can be searched.
            throw SnapException("BI-SNAP access token refused: responseCode=" + responseCode +
                " " + MidtransJson.text(body, "responseMessage"))
        }

        val expiresIn = parseExpiry(MidtransJson.text(body, "expiresIn"))
        val refreshAfter = now().plusSeconds(maxOf(1L, expiresIn - REFRESH_MARGIN_SECONDS))
        LOG.debugf("BI-SNAP access token acquired, refreshing after %s", refreshAfter)
        return Cached(token, refreshAfter)
    }

    /** `expiresIn` arrives as a JSON *string* ("900"), not a number. Trimmed by Java's rule
     * (code points up to U+0020), as `Long.parseLong(expiresIn.trim())` was. */
    private fun parseExpiry(expiresIn: String?): Long = try {
        expiresIn!!.trim { it <= ' ' }.toLong()
    } catch (e: RuntimeException) {
        // Rather than failing the charge over a field we can default sensibly: 900 is what the
        // standard specifies and what sandbox returned. Logged, because a changed contract
        // should be visible.
        LOG.warnf("Unparseable expiresIn '%s'; assuming 900s", expiresIn)
        900
    }

    /** The response body parsed, with a failure reported exactly as it was when the parse still
     * lived inside [exchange]. */
    private fun parse(body: String): JsonElement = try {
        MidtransJson.parseResponse(body)
    } catch (e: Exception) {
        throw SnapException("BI-SNAP access token request failed", e)
    }

    /**
     * Seam for tests, which must not open sockets. Returns the raw body rather than a parsed tree,
     * so a test stub hands back the text Midtrans would send and needs no JSON library of its own.
     * Public because Kotlin has no package-private, and Java cannot override a public method with
     * a package-private one — the stubs declare it public.
     */
    fun exchange(request: HttpRequest): String {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SnapException("Interrupted acquiring BI-SNAP access token", e)
        } catch (e: Exception) {
            throw SnapException("BI-SNAP access token request failed", e)
        }
    }

    /** Seam for tests, so expiry can be exercised without sleeping. */
    fun now(): Instant = Instant.now()

    /**
     * ISO-8601 with offset. The exact string is reused for the header and the stringToSign, since
     * Midtrans's pages disagree about the format and byte-identity is the only invariant that
     * actually holds.
     */
    fun timestamp(): String = OffsetDateTime.ofInstant(now(), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    /** Unchecked, matching `HttpMidtransGateway.MidtransException`: the charge path lets it
     * surface and the reconciliation sweep catches it per payment. */
    class SnapException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(SnapAccessTokenProvider::class.java)

        /** Refresh this long before nominal expiry, covering clock skew and the request's own flight
         * time. Generous because the cost of refreshing early is one cheap call; the cost of being
         * late is a misdiagnosed 401. */
        internal const val REFRESH_MARGIN_SECONDS = 60

        internal const val PATH = "/v1.0/access-token/b2b"
    }
}
