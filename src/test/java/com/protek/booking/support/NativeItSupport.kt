package com.protek.booking.support

import io.restassured.RestAssured.given
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.UUID
import java.util.regex.Pattern

/**
 * HTTP-only helpers for `@QuarkusIntegrationTest` classes, which run against the
 * packaged (often native) binary as a genuinely separate OS process — no CDI injection is
 * available there, unlike `@QuarkusTest`'s `TestFixture`. Each caller should
 * create its own dedicated showing via [createShowing] rather than reusing the
 * shared demo showing, so it can never collide with — or be disrupted by — another IT class
 * sharing the same running process and database within one `mvn verify` run.
 *
 * An `object` with `@JvmStatic` members, so the Java ITs' `NativeItSupport.signIn(...)` still
 * compiles (pattern 3).
 */
object NativeItSupport {

    private val SEAT_ID = Pattern.compile("name=\"seatIds\" value=\"([0-9a-fA-F-]{36})\"")

    private val STATE = Pattern.compile("[?&]state=([^&]+)")

    /**
     * Signs in to the binary as `email` through [FakeGoogleOidc], and returns the
     * session cookies to send with every later request. The test class must carry
     * `@WithTestResource(FakeGoogleOidc.class)`.
     *
     * Plays the browser's part of the code flow by hand. `/login` answers with a 302 to
     * the provider and sets the state cookie. Rather than visit the provider, this calls
     * `/callback` directly, with the email as the code (`FakeGoogleOidc`'s
     * convention) and the `state` copied off that redirect. The app then exchanges the
     * code, verifies the ID token and sets its session cookie. The redirect names
     * `https://` because of `force-redirect-https-scheme` (the Cloud Run front end
     * terminates TLS), so the plain-HTTP callback is built from the path rather than followed.
     */
    @JvmStatic
    fun signIn(email: String): Map<String, String> {
        val login = given().redirects().follow(false).`when`().get("/login")
        if (login.statusCode() != 302) {
            throw IllegalStateException("/login did not start a code flow: " + login.statusCode())
        }
        val state = STATE.matcher(login.getHeader("Location"))
        if (!state.find()) {
            throw IllegalStateException("no state in the provider redirect: " + login.getHeader("Location"))
        }
        val callback = given().redirects().follow(false)
            .cookies(login.cookies)
            .urlEncodingEnabled(false)
            .`when`().get("/callback?code=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&state=" + state.group(1))
        if (callback.statusCode() != 302 || callback.cookies.isEmpty()) {
            throw IllegalStateException("sign-in as " + email + " failed at /callback: "
                    + callback.statusCode() + " " + callback.body.asString())
        }
        return HashMap(callback.cookies)
    }

    /** Creates a fresh showing (opens `on_sale` immediately, per the admin form's own
     * label) under the given hall and returns its session id, discovered by scraping the
     * admin hall page for the row matching the unique label just submitted. */
    @JvmStatic
    fun createShowing(poolId: UUID, label: String): UUID {
        val starts = LocalDateTime.now().plusDays(1).withNano(0)
        val ends = starts.plusHours(2)

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("label", label)
            .formParam("startsAt", starts.toString())
            .formParam("endsAt", ends.toString())
            .redirects().follow(false)
            .`when`().post("/admin/halls/{poolId}/showings", poolId)
            .then().statusCode(303)

        val hallPage = given().`when`().get("/admin/halls/{poolId}", poolId)
            .then().statusCode(200).extract().asString()

        val rowPattern = Pattern.compile(
            "<td>" + Pattern.quote(label) + "</td>.*?/admin/showings/([0-9a-fA-F-]{36})/reservations",
            Pattern.DOTALL)
        val m = rowPattern.matcher(hallPage)
        if (!m.find()) {
            throw IllegalStateException("Could not find newly created showing '" + label + "' on admin hall page")
        }
        return UUID.fromString(m.group(1))
    }

    /** Scrapes the seat-map fragment for the first currently-available seat's resource
     * unit id (rendered only for seats whose `seatStatus == 'available'`, per
     * `seatmapFragment.html`). */
    @JvmStatic
    fun firstAvailableSeat(poolId: UUID, sessionId: UUID): UUID {
        val fragment = given().`when`()
            .get("/halls/{poolId}/showings/{sessionId}/fragment", poolId, sessionId)
            .then().statusCode(200).extract().asString()
        val m = SEAT_ID.matcher(fragment)
        if (!m.find()) {
            throw IllegalStateException("No available seat found in fragment for session " + sessionId)
        }
        return UUID.fromString(m.group(1))
    }
}
