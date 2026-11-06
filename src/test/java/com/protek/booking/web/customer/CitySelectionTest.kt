package com.protek.booking.web.customer

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The city select in the header, and the two ways it is set.
 *
 * Driven over HTTP rather than against `CityPreference` directly, because the two
 * things worth pinning down here only exist at that level: the cookie round trip, and the fact
 * that a GET never writes one.
 *
 * Reads the demo catalogue V1 seeds (Jakarta Pusat and Rekaan, both with coordinates) rather
 * than seeding its own branches. Deliberate: those two rows are what a fresh checkout shows, so
 * a test that invented its own would leave "does the shipped demo actually demonstrate this"
 * unasserted.
 */
@QuarkusTest
class CitySelectionTest {

    @Test
    fun theHeaderOffersEveryCityTheTenantOperatesIn() {
        val page = given().`when`().get("/").then().statusCode(200)
            .extract().asString()

        assertTrue(page.contains("<select id=\"city-field\""),
            "the select is the control the flow's step 2 describes; without it there is no "
                    + "city step at all")
        assertTrue(page.contains("Jakarta Pusat"))
        assertTrue(page.contains("Rekaan"),
            "the demo seed seeds a second city precisely so the select has something to choose between")
    }

    @Test
    fun withNoCookieTheDefaultCityIsMatchedAsAPrefix() {
        // booking.default-city is "Jakarta" and the seeded city is "Jakarta Pusat". An
        // exact-match-only default would silently never fire and every visitor would land on
        // whatever sorts first — which is the bug this asserts against, not a nicety.
        val page = given().`when`().get("/cinemas").then().statusCode(200).extract().asString()

        assertTrue(page.contains("Cinemas in Jakarta Pusat"),
            "expected the prefix match to select Jakarta Pusat, page was:\n" + page)
    }

    @Test
    fun choosingACityRemembersItAcrossPages() {
        val cookie = given().redirects().follow(false)
            .formParam("city", "Rekaan").formParam("next", "/cinemas")
            .`when`().post("/city")
            .then().statusCode(303).header("Location", containsString("/cinemas"))
            .extract().cookie(CityPreference.COOKIE)

        assertNotNull(cookie)
        given().cookie(CityPreference.COOKIE, cookie)
            .`when`().get("/cinemas")
            .then().statusCode(200).body(containsString("Cinemas in Rekaan"))
    }

    @Test
    fun aGetNeverWritesTheCookie() {
        // The property the crawler depends on: /cinemas renders the configured default on every
        // visit rather than a value that drifts with whatever was fetched before it. Also what
        // keeps CityPreference a pure read.
        val response: io.restassured.response.Response =
            given().`when`().get("/cinemas").then().statusCode(200).extract().response()

        assertFalse(response.cookies.containsKey(CityPreference.COOKIE),
            "a GET set " + CityPreference.COOKIE + ", which makes the same URL render "
                    + "differently depending on what was fetched before it")
    }

    @Test
    fun aCookieNamingACityTheTenantNoLongerOperatesInFallsBackRatherThanRenderingNothing() {
        given().cookie(CityPreference.COOKIE, "Atlantis")
            .`when`().get("/cinemas")
            .then().statusCode(200)
            // Falls back to the default rather than to an empty page titled "Cinemas in
            // Atlantis". Reachable without anybody misbehaving: a tenant closing their last
            // branch in a city leaves every returning visitor holding exactly this cookie.
            .body(containsString("Cinemas in Jakarta Pusat"))
    }

    @Test
    fun sharingALocationPicksTheNearestCityAndRemembersIt() {
        // Standing outside the Rekaan branch. The nearest-city answer has to beat the configured
        // default, or sharing a location would be a no-op for everybody in Central Java.
        val cookie = given()
            .formParam("latitude", "-7.0870").formParam("longitude", "110.9150")
            .`when`().post("/city/nearest")
            .then().statusCode(200).body("city", equalTo("Rekaan"))
            .extract().cookie(CityPreference.COOKIE)

        given().cookie(CityPreference.COOKIE, cookie)
            .`when`().get("/cinemas")
            .then().statusCode(200).body(containsString("Cinemas in Rekaan"))
    }

    @Test
    fun aVisitorInJakartaIsSentToJakarta() {
        given().formParam("latitude", "-6.2000").formParam("longitude", "106.8200")
            .`when`().post("/city/nearest")
            .then().statusCode(200).body("city", equalTo("Jakarta Pusat"))
    }

    @Test
    fun theReturnPathCannotBeTurnedIntoAnOpenRedirect() {
        // `next` arrives in a form field, so it is caller-supplied. "//evil.example" is the
        // shape that matters: a browser reads it as a protocol-relative absolute URL, and it
        // passes any check that only asks whether the value starts with a slash.
        given().redirects().follow(false)
            .formParam("city", "Rekaan").formParam("next", "//evil.example/phish")
            .`when`().post("/city")
            .then().statusCode(303).header("Location", endsWith("/"))

        given().redirects().follow(false)
            .formParam("city", "Rekaan").formParam("next", "https://evil.example/phish")
            .`when`().post("/city")
            .then().statusCode(303).header("Location", endsWith("/"))
    }
}
