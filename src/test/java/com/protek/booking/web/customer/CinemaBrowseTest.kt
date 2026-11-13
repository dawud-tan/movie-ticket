package com.protek.booking.web.customer

import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The two browse menus and the showtime strip they share.
 *
 * **What makes this worth a test class rather than a click-through.** The two menus are
 * the same grid entered from opposite corners, and the thing that must hold is that they agree:
 * the same film at the same branch has to offer the same showtimes whichever way the visitor
 * arrived. A regression there is invisible on either page alone.
 */
@QuarkusTest
class CinemaBrowseTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    private data class Playing(val site: Site, val hall: ResourcePool, val film: Film, val showing: ResourcePoolSession)

    /** One film, playing once, at one branch of the configured tenant. */
    private fun seedOneShowing(cityName: String): Playing {
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema " + cityName, cityName)
        val hall: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 3")
        val film: Film = fixture.film(tenant, "Test Film " + java.util.UUID.randomUUID(), "SU")
        val showing: ResourcePoolSession = fixture.sessionOfFilm(tenant, hall, film, "on_sale")
        return Playing(site, hall, film, showing)
    }

    @Test
    fun aCinemaPageListsWhatIsPlayingThereRatherThanItsStudios() {
        val playing = seedOneShowing("Rekaan-" + java.util.UUID.randomUUID())

        given().`when`().get("/cinemas/" + playing.site.slug)
            .then().statusCode(200)
            .body(containsString(playing.site.name))
            // The whole reason /sites/{ref} was replaced rather than kept alongside: a
            // customer standing outside a cinema wants the films, not a list of rooms.
            .body(containsString(playing.film.title))
    }

    @Test
    fun aFilmPageListsTheCinemasShowingIt() {
        val playing = seedOneShowing("Kudus-" + java.util.UUID.randomUUID())

        given().`when`().get("/movies/" + playing.film.slug)
            .then().statusCode(200)
            .body(containsString(playing.film.title))
            .body(containsString(playing.site.name))
    }

    @Test
    fun aTitlePlayingInFourStudiosIsOneRowOnTheFilmPage() {
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Multiplex", "Multiplex-" + java.util.UUID.randomUUID())
        val film: Film = fixture.film(tenant, "Opening Night " + java.util.UUID.randomUUID(), "SU")
        for (studio in 1..4) {
            fixture.sessionOfFilm(tenant, fixture.poolAtSite(tenant, site, "Studio " + studio),
                film, "on_sale")
        }

        val page = given().`when`().get("/movies/" + film.slug)
            .then().statusCode(200).extract().asString()

        // The chain case the film query's EXISTS shape exists for, asserted from the outside: a branch
        // running one title in every room is one entry, not four. Pattern.split, because that is
        // Java's String.split, limit and all (pattern 27).
        val occurrences = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote("/cinemas/" + site.slug)).split(page, -1).size - 1
        assertEquals(2, occurrences,
            "expected the branch to appear once as a link and once as the strip's hx-get "
                    + "target, got " + occurrences + " references")
    }

    @Test
    fun bothMenusOpenTheSameShowtimeStrip() {
        val playing = seedOneShowing("Blora-" + java.util.UUID.randomUUID())

        val fromCinema = given().`when`()
            .get("/cinemas/" + playing.site.slug + "/films/" + playing.film.slug)
            .then().statusCode(200).extract().asString()
        val fromFilm = given().`when`()
            .get("/movies/" + playing.film.slug + "/cinemas/" + playing.site.slug)
            .then().statusCode(200).extract().asString()

        // Byte-identical, because it is one template rendered from one query. Two renderings of
        // a showtime strip would be two places for "is this on sale" to drift apart, and the
        // drift would only show to a customer who used the other menu.
        assertEquals(fromCinema, fromFilm)
        assertTrue(fromCinema.contains(playing.showing.resourcePoolSessionId.toString()),
            "the strip has to carry the session id — it is what the booking POST sends")
    }

    @Test
    fun aShowtimeIsAPostAndNotALink() {
        val playing = seedOneShowing("Rembang-" + java.util.UUID.randomUUID())

        val strip = given().`when`()
            .get("/cinemas/" + playing.site.slug + "/films/" + playing.film.slug)
            .then().statusCode(200).extract().asString()

        // Opening a booking creates a booking_draft row, so it must not be reachable by GET: a
        // crawler walking the cinema page would otherwise mint one draft per showtime, and a
        // prefetching browser would do it before the customer decided anything.
        assertTrue(strip.contains("hx-post=\"/booking\""))
        assertTrue(strip.contains("action=\"/booking\""),
            "the plain form action is what makes the flow work with no JavaScript")
        assertFalse(strip.contains("href=\"/booking"),
            "a showtime must never be an anchor to /booking")
    }

    @Test
    fun aMistypedBranchIs404AndNotAnEmptyStrip() {
        val playing = seedOneShowing("Pati-" + java.util.UUID.randomUUID())

        // The two must not look alike: an empty strip means "nothing on today", which is a
        // sentence about the schedule rather than about the URL.
        given().`when`().get("/cinemas/no-such-branch/films/" + playing.film.slug)
            .then().statusCode(404)
        given().`when`().get("/movies/no-such-film/cinemas/" + playing.site.slug)
            .then().statusCode(404)
    }

    @Test
    fun aFilmWithNoShowingAnywhereIsNotOnTheNowPlayingList() {
        val tenant: Tenant = currentTenant.get()
        val shelved: Film = fixture.film(tenant, "Shelved " + java.util.UUID.randomUUID(), "SU")

        given().`when`().get("/movies").then().statusCode(200)
            .body(org.hamcrest.Matchers.not(containsString(shelved.title)))
    }

    @Test
    fun aShowingThatHasAlreadyStartedIsNotOfferedAsAShowtime() {
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Yesterday", "Yesterday-" + java.util.UUID.randomUUID())
        val hall: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 1")
        val film: Film = fixture.film(tenant, "Already Started " + java.util.UUID.randomUUID(), "SU")
        val past: ResourcePoolSession = fixture.sessionOfFilmAt(tenant, hall, film,
            Instant.now().minus(Duration.ofHours(3)), "on_sale")

        given().`when`().get("/cinemas/" + site.slug + "/films/" + film.slug)
            .then().statusCode(200)
            .body(org.hamcrest.Matchers.not(
                containsString(past.resourcePoolSessionId.toString())))
    }

    @Test
    fun theOldPathsRedirectPermanentlyOntoTheNewOnes() {
        val playing = seedOneShowing("Demak-" + java.util.UUID.randomUUID())

        // 301 and not 302: these URLs are in the submitted sitemap and in pasted links, and a
        // permanent redirect is what moves the accumulated signal onto the new address.
        given().redirects().follow(false).`when`().get("/films")
            .then().statusCode(301).header("Location", containsString("/movies"))
        given().redirects().follow(false).`when`().get("/films/" + playing.film.slug)
            .then().statusCode(301)
            .header("Location", containsString("/movies/" + playing.film.slug))
        given().redirects().follow(false).`when`().get("/sites/" + playing.site.slug)
            .then().statusCode(301)
            .header("Location", containsString("/cinemas/" + playing.site.slug))
    }

    @Test
    fun aUuidLinkRedirectsOntoTheSlugRatherThanOntoASecondSpellingOfTheNewAddress() {
        val playing = seedOneShowing("Salatiga-" + java.util.UUID.randomUUID())

        // The slug rule is that both forms resolve and the slug is canonical. Passing the uuid
        // through the redirect would honour the first half and quietly break the second.
        given().redirects().follow(false).`when`().get("/sites/" + playing.site.siteId)
            .then().statusCode(301)
            // endsWith, not equalTo: JAX-RS resolves a relative Location against the
            // request URI, so the wire value is absolute. The assertion that matters is that
            // the path is the SLUG and not the uuid that was asked for.
            .header("Location", endsWith("/cinemas/" + playing.site.slug))
    }

    @Test
    fun theOldCustomerOrderPathRedirectsToo() {
        given().redirects().follow(false).`when`().get("/reservations")
            .then().statusCode(301).header("Location", containsString("/orders"))
    }
}
