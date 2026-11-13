package com.protek.booking.payment.snap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the access-token cache.
 *
 * The token is good for 900 seconds — measured, not assumed: the sandbox probe returned
 * `expiresIn` 900 and a JWT whose own `exp - iat` was exactly 900. Fetching one per
 * charge would double the round trips on the path a customer waits on, so the caching is the point
 * and is what these tests are about.
 */
class SnapAccessTokenProviderTest {

    @TempDir
    lateinit var dir: Path

    /** A provider that never opens a socket: the HTTP exchange and the clock are both seams. */
    class StubProvider : SnapAccessTokenProvider() {
        val fetches = AtomicInteger()
        val requests: MutableList<HttpRequest> = CopyOnWriteArrayList()
        var now: Instant = Instant.parse("2026-08-15T00:00:00Z")
        var responseCode = "2007300"
        var expiresIn = "900"

        // Returns the body text Midtrans would send; the provider parses it.
        override fun exchange(request: HttpRequest): String {
            fetches.incrementAndGet()
            requests.add(request)
            return "{\"responseCode\":\"" + responseCode +
                "\",\"responseMessage\":\"Successful\",\"accessToken\":\"token-" +
                fetches.get() + "\",\"tokenType\":\"Bearer\",\"expiresIn\":\"" +
                expiresIn + "\"}"
        }

        override fun now(): Instant = now
    }

    @Test
    fun aTokenIsFetchedOnce_andReusedWhileItIsStillGood() {
        val provider = provider()

        assertEquals("token-1", provider.token())
        assertEquals("token-1", provider.token())
        assertEquals("token-1", provider.token())
        assertEquals(1, provider.fetches.get(),
            "refetching per call would double the round trips on the path a customer is " +
                "waiting on, which is the whole reason this class exists")
    }

    @Test
    fun theTokenIsRefreshedEarly_notAtTheMomentItExpires() {
        val provider = provider()
        provider.token()

        // 900s lifetime, 60s margin: still cached at 839s, refetched at 841s. A token that
        // expires between our check and Midtrans reading it returns 4014701, which at the call
        // site is indistinguishable from a signing error.
        provider.now = provider.now.plusSeconds(839)
        assertEquals("token-1", provider.token())
        assertEquals(1, provider.fetches.get())

        provider.now = provider.now.plusSeconds(2)
        assertEquals("token-2", provider.token())
        assertEquals(2, provider.fetches.get())
    }

    @Test
    fun invalidate_forcesTheNextCallToRefetch() {
        val provider = provider()
        assertEquals("token-1", provider.token())

        provider.invalidate()

        assertEquals("token-2", provider.token(),
            "a 401 that survives a retry needs a way to drop a token the server has stopped " +
                "accepting before its nominal expiry")
    }

    @Test
    fun concurrentCallersShareOneFetch_ratherThanEachFiringTheirOwn() {
        val provider = provider()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = pool.invokeAll(Collections.nCopies(threads,
                Callable {
                    barrier.await()
                    provider.token()
                }))
            for (result in results) {
                assertEquals("token-1", result.get())
            }
            // Several near-simultaneous token requests are exactly the shape that trips rate
            // limiting on someone else's infrastructure, so this is not merely wasteful.
            assertEquals(1, provider.fetches.get(),
                "a burst of concurrent charges must produce one token fetch, not one each")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aRefusedTokenThrowsWithMidtransOwnResponseCode() {
        val provider = provider()
        provider.responseCode = "4017300"

        val e = assertThrows(SnapAccessTokenProvider.SnapException::class.java) { provider.token() }
        // Every failure in this integration reads the same at the call site, so the code has to
        // reach the log or there is nothing to search the docs for.
        assertTrue(e.message!!.contains("4017300"), e.message)
    }

    @Test
    fun theAccessTokenCallCarriesNoPartnerIdAndNoChannelId() {
        // The property that made this call usable as an isolated probe: it authenticates with the
        // key alone, so a success proves the submitted public key was stored usably, independently
        // of the Partner ID question that took a measurement to settle.
        val provider = provider()
        provider.token()

        val request = provider.requests[0]
        assertTrue(request.headers().firstValue("X-PARTNER-ID").isEmpty)
        assertTrue(request.headers().firstValue("CHANNEL-ID").isEmpty)
        assertTrue(request.headers().firstValue("X-CLIENT-KEY").isPresent)
        assertTrue(request.headers().firstValue("X-SIGNATURE").isPresent)
        assertEquals(request.headers().firstValue("X-TIMESTAMP").orElseThrow().length,
            provider.timestamp().length,
            "the header timestamp must be the same shape as the one signed; byte-identity is " +
                "the only invariant that holds, since Midtrans's own pages disagree " +
                "about the format")
    }

    @Test
    fun aNonNumericExpiresIn_fallsBackToTheStandardLifetimeRatherThanFailingTheCharge() {
        val provider = provider()
        provider.expiresIn = "not-a-number"

        assertEquals("token-1", provider.token())
        provider.now = provider.now.plusSeconds(839)
        assertEquals("token-1", provider.token(), "should have assumed the standard 900s")
        assertEquals(1, provider.fetches.get())
    }

    private fun provider(): StubProvider {
        val provider = StubProvider()
        provider.credentials = credentials()
        provider.baseUrl = "https://merchants.sbx.example"
        provider.timeoutSeconds = 5
        return provider
    }

    /** A genuinely loaded SnapCredentials — `configured` is private, so the only way to get one is
     * through the real load path, which also keeps this test honest about that path working. */
    private fun credentials(): SnapCredentials {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val ours = generator.generateKeyPair()
        val theirs = generator.generateKeyPair()

        val credentials = SnapCredentials()
        credentials.clientId = "CLIENT-1"
        credentials.clientSecret = "SECRET-1"
        credentials.partnerId = "G000000000"
        credentials.channelId = "12345"
        credentials.privateKeyPath = writePem(ours.private.encoded, "PRIVATE KEY", "k.pem")
        credentials.midtransPublicKeyPath = writePem(theirs.public.encoded, "PUBLIC KEY", "m.pem")
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
