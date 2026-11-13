package com.protek.booking.i18n

import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What a translated page tells a crawler: which URL it is, and which URLs are it in the other
 * language.
 *
 * Runs under a configured public origin for the same reason `PublicMarkupTest` does —
 * with none there is deliberately no canonical and no `hreflang` to assert on, and
 * `UnconfiguredOriginTest` covers that half.
 */
@QuarkusTest
@TestProfile(LocalizedMarkupTest.PublicOrigin::class)
class LocalizedMarkupTest {

    companion object {
        const val ORIGIN = "https://cinema.example:8443"

        /** The canonical href, for a failure message that says what was actually claimed rather than
         * only that the expected string was absent. */
        private fun canonicalOf(page: String): String {
            val at = page.indexOf("rel=\"canonical\" href=\"")
            if (at < 0) {
                return "(no canonical)"
            }
            val from = at + "rel=\"canonical\" href=\"".length
            return page.substring(from, page.indexOf('"', from))
        }

        private fun get(path: String): String =
            given().get(path).then().statusCode(200).extract().body().asString()
    }

    class PublicOrigin : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            java.util.Map.of("booking.public.https-base-url", ORIGIN)
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    @BeforeEach
    fun aBranchExistsSoTheHomePageIsTheTranslatedOne() {
        val tenant = currentTenant.get()
        fixture.site(tenant, "Hreflang Demo " + UUID.randomUUID(), "Salatiga")
    }

    @Test
    fun theEnglishHomePageCanonicalisesToItselfAndNamesBothLanguages() {
        val page = get("/")

        assertTrue(page.contains("<link rel=\"canonical\" href=\"" + ORIGIN + "/\">"))
        assertTrue(page.contains("hreflang=\"en\" href=\"" + ORIGIN + "/\""))
        assertTrue(page.contains("hreflang=\"id\" href=\"" + ORIGIN + "/id\""))
        // x-default is what a visitor gets when nothing is known about them, which is the
        // unprefixed URL by construction.
        assertTrue(page.contains("hreflang=\"x-default\" href=\"" + ORIGIN + "/\""))
    }

    @Test
    fun theIndonesianHomePageCanonicalisesToThePrefixedUrlRatherThanBackToEnglish() {
        // The mistake worth a test: canonicalising /id back to / tells the crawler the
        // Indonesian page is a duplicate and takes it straight out of the index — which is the
        // whole thing the prefix was introduced to avoid.
        val page = get("/id")

        assertTrue(page.contains("<link rel=\"canonical\" href=\"" + ORIGIN + "/id\">"),
            "the Indonesian home page is its own canonical")
        assertTrue(page.contains("hreflang=\"en\" href=\"" + ORIGIN + "/\""))
        assertTrue(page.contains("hreflang=\"id\" href=\"" + ORIGIN + "/id\""))
    }

    @Test
    fun theHreflangSetIsSelfReferentialInBothDirections() {
        // A set that omits the page carrying it is discarded wholesale by search engines, and
        // it is the easy half to leave out — the English page pointing only at Indonesian
        // looks complete.
        for (url in arrayOf("/", "/id")) {
            val page = get(url)
            assertTrue(page.contains("hreflang=\"en\""), url + " names English")
            assertTrue(page.contains("hreflang=\"id\""), url + " names Indonesian")
        }
    }

    @Test
    fun aTranslatedPageNamesBothLanguagesAndIsItsOwnCanonicalInEach() {
        // /movies is the page that used to stand for "not translated yet" here, and both halves
        // of what it now asserts were the two failure modes of that state: a prefixed URL
        // canonicalising back to the English one takes the Indonesian page out of the index, and
        // an hreflang set that omits the page carrying it is discarded wholesale.
        val english = get("/movies")
        val indonesian = get("/id/movies")

        assertTrue(english.contains("<link rel=\"canonical\" href=\"" + ORIGIN + "/movies\">"))
        assertTrue(indonesian.contains("<link rel=\"canonical\" href=\"" + ORIGIN + "/id/movies\">"),
            "the Indonesian film menu is its own canonical, not a duplicate of the English one")
        for (page in arrayOf(english, indonesian)) {
            assertTrue(page.contains("hreflang=\"en\" href=\"" + ORIGIN + "/movies\""))
            assertTrue(page.contains("hreflang=\"id\" href=\"" + ORIGIN + "/id/movies\""))
        }
    }

    @Test
    fun aPageThatWasNotGivenAlternatesClaimsNoneAtAll() {
        // The rule this pins outlived its first example. It used to be demonstrated by /movies,
        // whose template was still English; now that every customer/ template is translated,
        // /cinemas is the one page left that deliberately carries a PageMeta and no alternates.
        // Its reason is different and permanent: it canonicalises to /cities/{city}, so a
        // self-referential set for it cannot exist. What is being asserted is the mechanism —
        // alternates are given per page, and a page not given them claims none.
        val page = get("/cinemas")

        assertTrue(page.contains("rel=\"canonical\""), "it still says which URL is the page")
        assertFalse(page.contains("hreflang="), "but claims no translation of its own")
    }

    @Test
    fun theIndonesianCinemaMenuCanonicalisesToTheIndonesianCityPageNotTheEnglishOne() {
        // The menu points somewhere else by design, and that made it the one place a language
        // could be dropped silently: naming /cities/X from /id/cinemas would hand a crawler the
        // English address for a page it is reading in Indonesian.
        val page = get("/id/cinemas")

        assertTrue(page.contains("<link rel=\"canonical\" href=\"" + ORIGIN + "/id/cities/"),
            "expected an /id/cities canonical, got: " + canonicalOf(page))
    }

    @Test
    fun theCopyOnATranslatedPageFollowsTheUrlAndNotOnlyTheHeader() {
        // The first converted templates (base.html, home.html) could pass the tests above while the
        // body stayed English, because the header alone carries a translated word. These two
        // strings come from movies.html and sites.html, not from base.html.
        assertTrue(get("/id/movies").contains("Belum ada yang dijual saat ini.") ||
                get("/id/movies").contains("Sedang tayang"),
            "movies.html renders its own copy in Indonesian")
        assertTrue(get("/id/cinemas").contains("Bioskop di "),
            "sites.html's heading is a message, not inline English")
    }

    @Test
    fun theOpenGraphDescriptionIsInThePagesOwnLanguage() {
        // og:description is what a link pasted into a WhatsApp group renders as — the way a
        // showtime actually travels in this market — so it follows the page rather than the
        // build.
        assertTrue(get("/").contains("content=\"Cinema showtimes and seat booking.\""))
        assertTrue(get("/id").contains("content=\"Jadwal tayang bioskop dan pemesanan kursi.\""),
            "PageMeta's copy comes from the bundle, not from a constant in Java")
    }
}
