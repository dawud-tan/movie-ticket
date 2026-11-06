package com.protek.booking.web.api

import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * `/api/app`: the anonymous browse path as JSON. Every call here
 * is made with no identity at all, because that is the whole claim — a phone that has not signed
 * in can walk city → cinema → film → showing → seat map.
 */
@QuarkusTest
class AppApiTest {

    @Inject
    lateinit var fixture: TestFixture

    @Inject
    lateinit var currentTenant: CurrentTenant

    private data class Showing(val site: Site, val film: Film, val pool: ResourcePool, val session: ResourcePoolSession)

    private fun showingAtABranch(city: String): Showing {
        val tenant = currentTenant.get()
        val site = fixture.site(tenant, "App Cinema " + System.nanoTime(), city)
        val pool = fixture.poolAtSite(tenant, site, "Studio 1")
        fixture.unit(tenant, pool, "A1", "A", 1)
        fixture.unit(tenant, pool, "A2", "A", 2)
        val film = fixture.film(tenant, "App Film " + System.nanoTime(), "SU")
        val session = fixture.sessionOfFilm(tenant, pool, film, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("45000"))
        return Showing(site, film, pool, session)
    }

    @Test
    fun theWholeBrowsePathAnswersJsonWithNoSignIn() {
        val s = showingAtABranch("Rekaan")

        given().`when`().get("/api/app/cities")
            .then().statusCode(200).body("city", hasItem("Rekaan"))

        given().`when`().get("/api/app/cities/Rekaan/cinemas")
            .then().statusCode(200).body("slug", hasItem(s.site.slug))

        given().`when`().get("/api/app/cinemas/" + s.site.slug)
            .then().statusCode(200)
            .body("cinema.name", equalTo(s.site.name))
            .body("movies.slug", hasItem(s.film.slug))

        given().`when`().get("/api/app/movies")
            .then().statusCode(200).body("slug", hasItem(s.film.slug))

        given().`when`().get("/api/app/movies/" + s.film.slug)
            .then().statusCode(200)
            .body("movie.title", equalTo(s.film.title))
            .body("movie.releaseDate", equalTo(s.film.releaseDate.toString()))
            .body("cinemas.slug", hasItem(s.site.slug))

        val showings: JsonPath = given().`when`()
            .get("/api/app/cinemas/" + s.site.slug + "/movies/" + s.film.slug + "/showings")
            .then().statusCode(200).extract().jsonPath()
        assertEquals(s.session.resourcePoolSessionId.toString(), showings.getString("[0].sessionId"))
        assertEquals(s.pool.resourcePoolId.toString(), showings.getString("[0].hallId"),
            "the seat map's address is hall + showing, so a showing has to carry both")
        assertEquals("Asia/Jakarta", showings.getString("[0].zone"),
            "the app has to group showings by the branch's day, not the phone's")
    }

    @Test
    fun aDateKeepsOneDayCountedInTheCinemasZoneNotUtc() {
        val s = showingAtABranch("Rekaan")
        val jakarta = ZoneId.of("Asia/Jakarta")
        val day = LocalDate.now(jakarta).plusDays(3)
        // 00:30 in Jakarta is 17:30 UTC the evening before: a UTC filter files it under the wrong day.
        val justAfterMidnight = fixture.sessionOfFilmAt(currentTenant.get(), s.pool, s.film,
            day.atTime(LocalTime.of(0, 30)).atZone(jakarta).toInstant(), "on_sale")
        val url = "/api/app/cinemas/" + s.site.slug + "/movies/" + s.film.slug + "/showings"

        given().queryParam("date", day.toString()).`when`().get(url)
            .then().statusCode(200)
            .body("sessionId", equalTo(java.util.List.of(justAfterMidnight.resourcePoolSessionId.toString())))
        given().queryParam("date", day.minusDays(1).toString()).`when`().get(url)
            .then().statusCode(200).body("size()", equalTo(0))
        given().`when`().get(url)
            .then().statusCode(200).body("size()", equalTo(2))
    }

    @Test
    fun aMalformedDateIsAJson400() {
        val s = showingAtABranch("Rekaan")
        given().queryParam("date", "15-10-2026")
            .`when`().get("/api/app/cinemas/" + s.site.slug + "/movies/" + s.film.slug + "/showings")
            .then().statusCode(400)
            .contentType("application/json")
            .body("error", equalTo("bad_request"))
    }

    @Test
    fun theSeatMapCarriesStatusAndPriceButNoReservationIds() {
        val s = showingAtABranch("Rekaan")
        val map: JsonPath = given().`when`()
            .get("/api/app/halls/" + s.pool.resourcePoolId + "/showings/" + s.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body("rows[0].rowLabel", equalTo("A"))
            .body("rows[0].seats.size()", equalTo(2))
            .body("rows[0].seats[0].status", equalTo("available"))
            .body("movieTitle", equalTo(s.film.title))
            .extract().jsonPath()
        assertEquals(0, BigDecimal("45000").compareTo(BigDecimal(map.getString("price.amount"))))
        val raw = map.prettify()
        assertFalse(raw.contains("reservationId"),
            "a stranger's reservation id is not on the web seat map and must not be here:\n" + raw)
        assertFalse(raw.contains("holdExpiresAt"), raw)
    }

    @Test
    fun theSeatMapStatesTheCapsTheHoldEnforces_andNoViabilityLineWhileThatRuleIsOff() {
        // application.properties' 8 and 10; %test turns the viability rule off (minimum 1).
        val s = showingAtABranch("Rekaan")
        given().`when`()
            .get("/api/app/halls/" + s.pool.resourcePoolId + "/showings/" + s.session.resourcePoolSessionId)
            .then().statusCode(200)
            .body("seatCapPerBooking", equalTo(8))
            .body("seatCapPerDay", equalTo(10))
            .body("viability", nullValue())
    }

    @Test
    fun aHallWithNoBranchIsListed_andItsPageListsItsShowings() {
        val tenant = currentTenant.get()
        val loose = fixture.pool(tenant)
        val showing = fixture.session(tenant, loose, "on_sale")
        val placed = showingAtABranch("Rekaan")

        given().`when`().get("/api/app/halls/unassigned")
            .then().statusCode(200)
            .body("hallId", hasItem(loose.resourcePoolId.toString()))
            .body("hallId", not(hasItem(placed.pool.resourcePoolId.toString())))

        given().header("Accept-Language", "en").`when`().get("/api/app/halls/" + loose.resourcePoolId)
            .then().statusCode(200)
            .body("name", equalTo("Test Hall"))
            .body("zone", equalTo("Asia/Jakarta"))
            .body("showings.sessionId", hasItem(showing.resourcePoolSessionId.toString()))
            .body("showings.label", hasItem("Test Showing"))
            .body("showings.statusLabel", not(hasItem(nullValue())))
    }

    @Test
    fun anUnknownHallIsAJson404() {
        given().`when`().get("/api/app/halls/" + java.util.UUID.randomUUID())
            .then().statusCode(404)
            .contentType("application/json")
            .body("error", not(nullValue()))
    }

    @Test
    fun anUnknownMovieIsAJson404NotAnHtmlPage() {
        given().`when`().get("/api/app/movies/no-such-film-" + System.nanoTime())
            .then().statusCode(404)
            .contentType("application/json")
            .body("error", not(nullValue()))
    }

    @Test
    fun nearestCityAnswersWithoutSettingACookie() {
        // Standing outside the Rekaan branch, as in CitySelectionTest. The app remembers the city
        // itself; a cookie would only be noise in its jar.
        val response: io.restassured.response.Response = given().contentType("application/json")
            .body("{\"latitude\":-7.0870,\"longitude\":110.9150}")
            .`when`().post("/api/app/cities/nearest")
            .then().statusCode(200).body("city", equalTo("Rekaan"))
            .extract().response()
        assertTrue(response.cookies.isEmpty(), "cookies: " + response.cookies)
    }

    @Test
    fun nearestCityIsSafeToRepeat() {
        // The one POST in this API. A phone's stack may resend a request whose connection died
        // before the answer came back; here that must give the same answer and change nothing.
        val body = "{\"latitude\":-6.2000,\"longitude\":106.8200}"
        val answers: MutableList<String> = java.util.ArrayList()
        for (i in 0 until 2) {
            answers.add(given().contentType("application/json").body(body)
                .`when`().post("/api/app/cities/nearest")
                .then().statusCode(200).extract().jsonPath().getString("city"))
        }
        assertEquals(java.util.List.of("Jakarta Pusat", "Jakarta Pusat"), answers)
    }

    @Test
    fun nearestCityRefusesAMissingCoordinateAsJson() {
        given().contentType("application/json").body("{\"latitude\":-7.0}")
            .`when`().post("/api/app/cities/nearest")
            .then().statusCode(400).body("error", equalTo("bad_request"))
    }

    @Test
    fun aCrossSiteFormCannotReachTheOnlyPost() {
        // The 415 a JSON-only endpoint relies on: a form on another site cannot send JSON.
        given().formParam("latitude", "-7.0").formParam("longitude", "110.9")
            .`when`().post("/api/app/cities/nearest")
            .then().statusCode(415)
    }

    @Test
    fun indonesianStatusLabelsFollowAcceptLanguage() {
        val s = showingAtABranch("Rekaan")
        val label: String? = given().header("Accept-Language", "id")
            .`when`().get("/api/app/halls/" + s.pool.resourcePoolId + "/showings/" + s.session.resourcePoolSessionId)
            .then().statusCode(200).extract().jsonPath().getString("rows[0].seats[0].statusLabel")
        assertNotNull(label)
        val english: String? = given().header("Accept-Language", "en")
            .`when`().get("/api/app/halls/" + s.pool.resourcePoolId + "/showings/" + s.session.resourcePoolSessionId)
            .then().statusCode(200).extract().jsonPath().getString("rows[0].seats[0].statusLabel")
        assertFalse(label!!.equals(english), "both languages gave \"" + label + "\"")
    }

    @Test
    fun theSeatMapGivesTheAppTheComplaintPhoneTheWebFooterShows() {
        val s = showingAtABranch("Rekaan")
        val phone = currentTenant.get().complaintPhone
        assertNotNull(phone)
        given().`when`().get("/api/app/halls/" + s.pool.resourcePoolId + "/showings/" + s.session.resourcePoolSessionId)
            .then().statusCode(200).body("complaintPhone", equalTo(phone))
            // The footer's PSE number: none recorded on the shared tenant, so none sent.
            .body("pseRegistrationNo", nullValue())
    }
}
