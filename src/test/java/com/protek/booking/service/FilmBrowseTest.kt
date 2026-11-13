package com.protek.booking.service

import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The film-first browse path.
 *
 * The question these exist to prove is answerable at all: "when and where is film X
 * playing?" Before the film table a showing's title was free text, so the same film in three studios
 * was three unrelated strings and there was no query that could group them.
 */
@QuarkusTest
class FilmBrowseTest {

    companion object {
        /** Every test runs under the one configured tenant, so titles accumulate across
         * methods — unique titles keep each test asserting about its own film. */
        private fun aTitle(name: String): String = name + "-" + UUID.randomUUID()
    }

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun oneFilmPlayingInManyStudiosIsOneEntryUnderNowPlaying() {
        // The chain case, and the reason findNowPlaying uses EXISTS rather than a join:
        // a title playing in forty studios must not appear forty times.
        val tenant: Tenant = currentTenant.get()
        val film: Film = fixture.film(tenant, aTitle("Pengabdi Setan"), "17+")
        fixture.sessionOfFilm(tenant, fixture.pool(tenant), film, "on_sale")
        fixture.sessionOfFilm(tenant, fixture.pool(tenant), film, "on_sale")
        fixture.sessionOfFilm(tenant, fixture.pool(tenant), film, "on_sale")

        val nowPlaying: List<Film> = catalogService.listNowPlaying()

        // Stream.count() is a long, which Java widened the expected int to.
        assertEquals(1L, nowPlaying.stream().filter { it.filmId == film.filmId }.count(),
            "a film playing in three studios must appear once")
    }

    @Test
    fun aFilmWithNoUpcomingShowingIsNotNowPlaying() {
        val tenant = currentTenant.get()
        val shelved = fixture.film(tenant, aTitle("Not Scheduled"), "SU")

        assertTrue(catalogService.listNowPlaying().none { it.filmId == shelved.filmId })
        // ...but it is still a film the operator can schedule.
        assertTrue(catalogService.listFilms().any { it.filmId == shelved.filmId })
    }

    @Test
    fun aFilmsShowingsNameTheBranchAndHallTheyPlayIn() {
        val tenant = currentTenant.get()
        val branch: Site = fixture.site(tenant, "Demo Cinema Rekaan", "Rekaan-" + UUID.randomUUID())
        val studio: ResourcePool = fixture.poolAtSite(tenant, branch, "Studio 2")
        val film = fixture.film(tenant, aTitle("Laskar Pelangi"), "SU")
        fixture.sessionOfFilm(tenant, studio, film, "on_sale")

        val showings: List<ResourcePoolSessionRepository.FilmShowing> =
            catalogService.listShowingsOfFilm(film.filmId)

        assertEquals(1, showings.size)
        val showing = showings[0]
        assertEquals("Demo Cinema Rekaan", showing.siteName)
        assertEquals(branch.siteId, showing.siteId)
        assertEquals("Studio 2", showing.hallName)
        assertEquals(studio.resourcePoolId, showing.resourcePoolId)
    }

    @Test
    fun aShowingInAnUnassignedHallStillAppearsUnderItsFilm() {
        // A hall with no branch must not vanish from the film-first path either.
        val tenant = currentTenant.get()
        val orphan = fixture.pool(tenant)
        val film = fixture.film(tenant, aTitle("Unbranched"), "13+")
        fixture.sessionOfFilm(tenant, orphan, film, "on_sale")

        val showings = catalogService.listShowingsOfFilm(film.filmId)

        assertEquals(1, showings.size)
        assertNull(showings[0].siteName, "an unassigned hall has no branch name")
        assertNotNull(showings[0].hallName, "but it still names its hall")
    }

    @Test
    fun aLabelOnlyShowingIsStillValidAndSimplyHasNoFilm() {
        // The schema is vertical-neutral: film_id is nullable because a label-only
        // session is what the hospitality mapping and every non-film event uses.
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)

        val session: ResourcePoolSession = fixture.session(tenant, hall, "on_sale")

        assertNull(session.filmId)
        assertEquals("Test Showing", session.label)
        assertTrue(catalogService.listUpcomingShowings(hall.resourcePoolId)
            .any { it.resourcePoolSessionId == session.resourcePoolSessionId },
            "a label-only showing must still browse venue-first")
    }

    @Test
    fun creatingAShowingOfAFilmWithoutALabelBorrowsTheFilmsTitle() {
        // resource_pool_session.label is NOT NULL and stays that way, so the film's title is
        // what fills it — the column keeps meaning what it always meant.
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        val title = aTitle("Ada Apa Dengan Cinta")
        val film = fixture.film(tenant, title, "13+")
        val starts: Instant = Instant.now().plus(1, ChronoUnit.DAYS)

        val created = catalogService.createShowingOfFilm(hall.resourcePoolId,
            film.filmId, "  ", starts, starts.plus(2, ChronoUnit.HOURS), starts.minusSeconds(900))

        assertEquals(title, created.label)
        assertEquals(film.filmId, created.filmId)
    }

    @Test
    fun anExplicitLabelIsKeptRatherThanOverwrittenByTheTitle() {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        val film = fixture.film(tenant, aTitle("Marlina"), "17+")
        val starts = Instant.now().plus(1, ChronoUnit.DAYS)

        val created = catalogService.createShowingOfFilm(hall.resourcePoolId,
            film.filmId, "Midnight premiere", starts, starts.plus(2, ChronoUnit.HOURS), starts.minusSeconds(900))

        assertEquals("Midnight premiere", created.label)
        assertEquals(film.filmId, created.filmId)
    }

    @Test
    fun aFilmCarriesItsClassificationForDisplay() {
        // the age attestation (enforcing it at hold time) is deliberately NOT built here — this only pins
        // that the rating is recorded and readable, which is what the film table delivers.
        val tenant = currentTenant.get()
        val film = fixture.film(tenant, aTitle("Rated"), "21+")

        assertEquals("21+", catalogService.getFilmOrThrow(film.filmId).classification)
    }
}
