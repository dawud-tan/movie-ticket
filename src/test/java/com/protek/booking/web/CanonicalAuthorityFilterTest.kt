package com.protek.booking.web

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.Socket
import java.net.URI
import java.util.UUID

/**
 * The booking app answers only under its canonical authority, and nothing else in this
 * deployment is affected by that.
 *
 * The second property is the one worth guarding hardest. A filter that decides by package is
 * easy to turn into one that decides by "everything", and in a deployment that hosts a second
 * application beside this one, that takes it dark with no obvious cause. What stands in for it
 * here is the other half of the same claim: a static file from
 * META-INF/resources has no JAX-RS resource class at all, the filter therefore never sees it,
 * and it must keep answering under any authority. Break the package rule into a blanket rule and
 * this is the test that notices.
 */
@QuarkusTest
@TestProfile(CanonicalAuthorityFilterTest.PinnedAuthority::class)
class CanonicalAuthorityFilterTest {

    companion object {
        private const val CANONICAL = "tickets.example:8443"
        private const val SOMEBODY_ELSE = "not-the-cinema.example"
    }

    @TestHTTPResource("/")
    lateinit var base: URI

    /** The status a WebSocket upgrade of the live seat map gets, sent by hand so that `Host` is
     * exactly [host]. Any showing will do: the authority is checked before the socket opens. */
    private fun upgradeStatus(host: String): Int = Socket(base.host, base.port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(("GET /api/app/live/showings/${UUID.randomUUID()}/${UUID.randomUUID()} HTTP/1.1\r\n" +
            "Host: $host\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
        socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
    }

    @Test
    fun theCinemaAnswersUnderItsCanonicalAuthority() {
        given().header("Host", CANONICAL)
            .`when`().get("/")
            .then().statusCode(200)
    }

    @Test
    fun theCinemaIs404UnderAnyOtherAuthority() {
        given().header("Host", SOMEBODY_ELSE)
            .`when`().get("/")
            .then().statusCode(404)
    }

    @Test
    fun theHostComparisonIgnoresCase() {
        // Hostnames are case-insensitive. Getting this wrong 404s the whole site the first time
        // somebody types the config value with a capital.
        given().header("Host", "TICKETS.Example:8443")
            .`when`().get("/")
            .then().statusCode(200)
    }

    @Test
    fun theRightPortIsPartOfTheAuthority() {
        // The port is not inferred from the scheme, so the same host on another port is a
        // different authority. This is what makes plain-HTTP 8080 a deliberate decision rather
        // than an accident: a server that lists only the :8443 authorities serves the cinema
        // https-only while 8080 keeps serving static pages.
        given().header("Host", "tickets.example:8080")
            .`when`().get("/")
            .then().statusCode(404)
    }

    @Test
    fun aBookingPathDeeperThanTheRootIsGuardedToo() {
        // Guarding is per resource class, not per route, so this should hold without the filter
        // knowing anything about /movies.
        given().header("Host", SOMEBODY_ELSE)
            .`when`().get("/movies")
            .then().statusCode(404)
        given().header("Host", CANONICAL)
            .`when`().get("/movies")
            .then().statusCode(200)
    }

    @Test
    fun theLiveSeatMapSocketUpgradesUnderTheCanonicalAuthority() {
        assertEquals(101, upgradeStatus(CANONICAL))
    }

    @Test
    fun theLiveSeatMapSocketIs404UnderAnyOtherAuthority_includingABareIpAddress() {
        // A websockets-next upgrade never reaches the REST filter, so before 2026-10-24 these were
        // answered with 101 (a server's bare IP address was found that way).
        assertEquals(404, upgradeStatus(SOMEBODY_ELSE))
        assertEquals(404, upgradeStatus("203.0.113.7:8443"))
    }

    @Test
    fun staticFilesAreUntouchedByTheAuthorityRule() {
        // The whole point of matching on the matched resource's package rather than on the path:
        // /robots.txt is served straight out of META-INF/resources with no resource class, so it
        // is outside the filter's reach and answers under any authority — including the one the
        // cinema itself refuses.
        given().header("Host", SOMEBODY_ELSE)
            .`when`().get("/robots.txt")
            .then().statusCode(200)
    }

    @Test
    fun theCinemaSendsItsContentSecurityPolicyAndNosniff() {
        given().header("Host", CANONICAL)
            .`when`().get("/")
            .then().statusCode(200)
            .header("Content-Security-Policy", containsString("script-src 'self' https://www.gstatic.com;"))
            .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
            .header("Content-Security-Policy-Report-Only", nullValue())
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "strict-origin-when-cross-origin")
            // geolocation stays allowed: the header's "Use my location" calls it.
            .header("Permissions-Policy", containsString("geolocation=(self)"))
            .header("Permissions-Policy", containsString("camera=()"))
    }

    @Test
    fun staticFilesGetNoContentSecurityPolicy() {
        // The policy rides on the same package rule, so what has no resource class gets none: in a
        // deployment that hosts a second application, its inline scripts and CDNs stay untouched.
        given().header("Host", SOMEBODY_ELSE)
            .`when`().get("/robots.txt")
            .then().statusCode(200)
            .header("Content-Security-Policy-Report-Only", nullValue())
            .header("Content-Security-Policy", nullValue())
    }

    class PinnedAuthority : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            // Unset everywhere else, which is why the rest of the suite still reaches localhost.
            mapOf("booking.canonical-authority" to CANONICAL)
    }
}
