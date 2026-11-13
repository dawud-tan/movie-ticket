package com.protek.booking.seo

import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The public pages' head markup — canonical, Open Graph and JSON-LD.
 *
 * Runs under a profile that configures a public origin, because that is the state a
 * deployment is in (`PUBLIC_HTTPS_BASE_URL=https://tickets.example:8443`) and
 * because with no origin there is deliberately no canonical to assert on.
 * [UnconfiguredOriginTest] covers the other half.
 *
 * Kotlin since 2026-10-08, parsing the JSON-LD with kotlinx.serialization. It used to inject
 * Quarkus's Jackson `ObjectMapper`, which went with `quarkus-rest-jackson` that day.
 */
@QuarkusTest
@TestProfile(PublicMarkupTest.PublicOrigin::class)
class PublicMarkupTest {

    class PublicOrigin : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("booking.public.https-base-url" to ORIGIN)
    }

    @Inject
    lateinit var fixture: TestFixture

    @Inject
    lateinit var currentTenant: CurrentTenant

    // --- Crawlable URLs -------------------------------------------------------------------

    @Test
    fun aBranchIsReachableByItsSlugAndStillByTheUuidThatPredatesIt() {
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, unique("Grand Cinema Rekaan"), "Rekaan")

        val bySlug = get("/cinemas/" + branch.slug)
        val byUuid = get("/cinemas/" + branch.siteId)

        assertTrue(bySlug.contains(branch.name!!), "the slug URL must serve the branch page")
        assertTrue(byUuid.contains(branch.name),
            "the uuid URL is what every pre-V16 link and bookmark uses — it must keep working")
    }

    @Test
    fun bothFormsOfABranchUrlNameTheSlugFormAsCanonical() {
        // The point of keeping two working URLs: a crawler that arrives either way is told
        // which one is the page, so nothing has to be redirected away.
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, unique("Grand Cinema"), "Semarang")
        val expected = "<link rel=\"canonical\" href=\"" + ORIGIN + "/cinemas/" + branch.slug + "\">"

        assertTrue(get("/cinemas/" + branch.slug).contains(expected))
        assertTrue(get("/cinemas/" + branch.siteId).contains(expected),
            "the uuid URL must point at the slug, not at itself")
    }

    @Test
    fun aFilmIsReachableByItsSlugAndByItsUuid() {
        val tenant = currentTenant.get()
        val film = fixture.film(tenant, unique("Pengabdi Setan"), "17+")

        assertTrue(get("/movies/" + film.slug).contains(film.title!!))
        assertTrue(get("/movies/" + film.filmId).contains(film.title))
    }

    @Test
    fun anUnknownSlugIsANotFoundRatherThanAnError() {
        given().get("/cinemas/no-such-branch").then().statusCode(404)
        given().get("/movies/no-such-film").then().statusCode(404)
    }

    // --- MovieTheater ---------------------------------------------------------------------

    @Test
    fun aBranchPageCarriesMovieTheaterMarkupWithTheAddressGoogleRequires() {
        // The site table is what made this expressible: `city` is NOT NULL, so addressLocality always has
        // a value. Before it, a "cinema" was a resource_pool row with free-text address and
        // no city at all — which is why this markup was not worth writing then.
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, unique("Demo Cinema Rekaan"), "Rekaan")
        fixture.poolAtSite(tenant, branch, "Studio 1")
        fixture.poolAtSite(tenant, branch, "Studio 2")

        val markup = jsonLdOf(get("/cinemas/" + branch.slug))

        assertEquals("MovieTheater", markup.path("@type").text())
        assertEquals(branch.name, markup.path("name").text())
        assertEquals("PostalAddress", markup.path("address").path("@type").text())
        assertEquals("Rekaan", markup.path("address").path("addressLocality").text())
        assertEquals("ID", markup.path("address").path("addressCountry").text())
        assertEquals("2", markup.path("screenCount").text())
        assertEquals(ORIGIN + "/cinemas/" + branch.slug, markup.path("url").text())
    }

    // --- Movie and ScreeningEvent ---------------------------------------------------------

    @Test
    fun aFilmPageCarriesMovieMarkupBuiltFromTheColumnsV10Added() {
        val tenant = currentTenant.get()
        val film = fixture.film(tenant, unique("Laskar Pelangi"), "SU")

        val movie = jsonLdOf(get("/movies/" + film.slug))

        assertEquals("Movie", movie.path("@type").text())
        assertEquals(film.title, movie.path("name").text())
        assertEquals("SU", movie.path("contentRating").text())
        assertEquals("Test synopsis", movie.path("description").text())
        // 118 minutes, as an ISO-8601 duration rather than a number — schema.org's Duration.
        assertEquals("PT1H58M", movie.path("duration").text())
        assertEquals(ORIGIN + "/movies/" + film.slug, movie.path("url").text())
    }

    @Test
    fun everyShowingBecomesAScreeningEventPointingAtTheSeatMapThatSellsIt() {
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, unique("Demo Cinema Solo"), "Solo")
        val studio = fixture.poolAtSite(tenant, branch, "Studio 3")
        val film = fixture.film(tenant, unique("Ada Apa Dengan Cinta"), "13+")
        val showing = fixture.sessionOfFilm(tenant, studio, film, "on_sale")

        val event = firstOfType(jsonLdNodes(get("/movies/" + film.slug)), "ScreeningEvent")

        assertNotNull(event, "the film page must carry a ScreeningEvent for its showing")
        // Two types deliberately: ScreeningEvent is the accurate one, Event is the one
        // Google's documentation names.
        assertEquals(listOf("Event", "ScreeningEvent"), typesOf(event!!))
        assertEquals(film.title, event.path("name").text())
        // workPresented is the entire reason to prefer ScreeningEvent over Event, and the film table is
        // what supplies something to point it at.
        assertEquals("Movie", event.path("workPresented").path("@type").text())
        assertEquals(film.title, event.path("workPresented").path("name").text())
        assertEquals("MovieTheater", event.path("location").path("@type").text())
        assertEquals(branch.name, event.path("location").path("name").text())
        assertEquals("Solo", event.path("location").path("address").path("addressLocality").text())

        val seatMap = ORIGIN + "/halls/" + studio.resourcePoolId +
            "/showings/" + showing.resourcePoolSessionId
        assertEquals(seatMap, event.path("url").text(),
            "an events result must land the customer on the page that sells the ticket")
        assertEquals(seatMap, event.path("offers").path("url").text())
        assertNull(event.path("offers").path("price"),
            "a calendar rule gives one showing four amounts — a single price here would be " +
                "wrong twice a week")
    }

    @Test
    fun aScreeningsStartDateCarriesTheBranchesOffsetAndNotTheHallsDefault() {
        // The trap calendar pricing already paid for, in a second place. resource_pool.timezone is NOT NULL
        // DEFAULT 'Asia/Jakarta', so a hall at a Makassar branch reads as +07:00 unless the
        // branch wins — and the events experience groups by local day, so an hour is a day.
        val tenant = currentTenant.get()
        val makassar = fixture.siteInZone(tenant, unique("Demo Cinema Makassar"), "Makassar", "Asia/Makassar")
        val studio = fixture.poolAtSite(tenant, makassar, "Studio 1")
        val film = fixture.film(tenant, unique("Wita Matinee"), "SU")
        fixture.sessionOfFilm(tenant, studio, film, "on_sale")

        val event = firstOfType(jsonLdNodes(get("/movies/" + film.slug)), "ScreeningEvent")

        assertNotNull(event)
        val startDate = event.path("startDate").text()
        assertTrue(startDate.endsWith("+08:00"),
            "the hall's Asia/Jakarta default must not outrank the branch's Asia/Makassar; " +
                "startDate was " + startDate)
    }

    // --- The escaping the whole thing turns on --------------------------------------------

    @Test
    fun aFilmTitleContainingAScriptTagCannotCloseTheJsonLdBlock() {
        // Staff type titles into the admin screens, so this is reachable from a form. Qute
        // HTML-escaping does not help inside a <script> element — its content is raw text, so
        // &#39; would arrive literally and break the JSON — and .raw on the title would be the
        // injection itself. JsonLd escapes < > & in code, where it can be guaranteed.
        val tenant = currentTenant.get()
        val hostile = "</script><script>alert(1)</script> " + UUID.randomUUID()
        val film = fixture.film(tenant, hostile, "17+")

        val page = get("/movies/" + film.slug)

        assertFalse(page.contains("<script>alert(1)</script>"),
            "the injected tag reached the page as markup")
        assertTrue(page.contains("\\u003C/script\\u003E"),
            "the title's angle brackets should be JSON unicode escapes")
        // And it is still valid JSON that still means what the operator typed.
        assertEquals(hostile, jsonLdOf(page).path("name").text())
    }

    @Test
    fun anAmpersandInATitleSurvivesRatherThanBeingDropped() {
        // "Fast & Furious" is an ordinary title. Escaping rather than rejecting is the whole
        // reason JsonLd rewrites & instead of refusing values that contain it.
        val tenant = currentTenant.get()
        val title = "Fast & Furious " + UUID.randomUUID()
        val film = fixture.film(tenant, title, "13+")

        assertEquals(title, jsonLdOf(get("/movies/" + film.slug)).path("name").text())
    }

    // --- Open Graph -----------------------------------------------------------------------

    @Test
    fun aFilmPageCarriesTheOpenGraphTagsAPastedWhatsAppLinkRendersFrom() {
        val tenant = currentTenant.get()
        val film = fixture.film(tenant, unique("Gundala"), "13+")

        val page = get("/movies/" + film.slug)

        assertTrue(page.contains("<meta property=\"og:title\" content=\"" + film.title + "\">"), page)
        assertTrue(page.contains("<meta property=\"og:description\" content=\"Test synopsis\">"))
        assertTrue(page.contains("<meta property=\"og:url\" content=\"" +
            ORIGIN + "/movies/" + film.slug + "\">"))
        assertTrue(page.contains("<meta property=\"og:type\" content=\"video.movie\">"),
            "a film is the one page with a type more specific than \"website\"")
    }

    // --- Sitemap --------------------------------------------------------------------------

    @Test
    fun theSitemapListsTheSlugFormOfEveryBranchAndEveryFilmOnSale() {
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, unique("Demo Cinema Kudus"), "Kudus")
        val studio = fixture.poolAtSite(tenant, branch, "Studio 1")
        val playing = fixture.film(tenant, unique("On Sale Now"), "SU")
        fixture.sessionOfFilm(tenant, studio, playing, "on_sale")
        val shelved = fixture.film(tenant, unique("Not Scheduled"), "SU")

        val sitemap = given().get("/sitemap-showtimes.xml").then().statusCode(200)
            .extract().body().asString()

        assertTrue(sitemap.contains("<loc>" + ORIGIN + "/cinemas/" + branch.slug + "</loc>"))
        assertTrue(sitemap.contains("<loc>" + ORIGIN + "/movies/" + playing.slug + "</loc>"))
        assertTrue(sitemap.contains("<loc>" + ORIGIN + "/halls/" + studio.resourcePoolId + "</loc>"),
            "hall pages are the crawl path to a seat map")
        assertFalse(sitemap.contains("/movies/" + shelved.slug + "<"),
            "a film with no upcoming showing is not on /movies either — submitting it is " +
                "asking to be judged on a page that says \"no upcoming showings\"")
        assertFalse(sitemap.contains("/cinemas/" + branch.siteId),
            "the sitemap must list one URL per page, and that is the slug form")
    }

    @Test
    fun theSitemapIsWellFormedXml() {
        val sitemap = given().get("/sitemap-showtimes.xml").then().statusCode(200)
            .extract().body().asString()

        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        // Namespace-aware on purpose, unlike the default: the xhtml: prefix the alternates
        // below use is only legal because <urlset> declares it, and a non-aware parser accepts
        // an undeclared prefix as though it were part of the element's name. That is the one
        // way this file can stop being XML without this test noticing.
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(InputSource(StringReader(sitemap)))
    }

    @Test
    fun everySitemapEntryIsListedInBothLanguagesAndNamesTheWholeSetIncludingItself() {
        // Before this, the sitemap listed only unprefixed URLs, so the only way a crawler
        // reached an /id page was by following an on-page hreflang from the English one.
        val tenant = currentTenant.get()
        val branch = fixture.site(tenant, unique("Demo Cinema Salatiga"), "Salatiga")

        val sitemap = given().get("/sitemap-showtimes.xml").then().statusCode(200)
            .extract().body().asString()

        val english = ORIGIN + "/cinemas/" + branch.slug
        val indonesian = ORIGIN + "/id/cinemas/" + branch.slug
        assertTrue(sitemap.contains("<loc>$english</loc>"), sitemap)
        assertTrue(sitemap.contains("<loc>$indonesian</loc>"),
            "the Indonesian spelling is a URL of its own and has to be listed as one")

        // Self-referential, which is the half that is easy to omit and fatal to omit: a search
        // engine discards an alternate set that does not name the URL carrying it. Same
        // requirement PublicUrls.alternates exists to make unmissable on the page itself.
        assertTrue(sitemap.contains("<xhtml:link rel=\"alternate\" hreflang=\"en\" href=\"$english\"/>"), sitemap)
        assertTrue(sitemap.contains("<xhtml:link rel=\"alternate\" hreflang=\"id\" href=\"$indonesian\"/>"),
            "the other language")
        assertTrue(sitemap.contains("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"$english\"/>"),
            "x-default is the unprefixed URL, by construction")
        assertTrue(sitemap.contains("xmlns:xhtml=\"http://www.w3.org/1999/xhtml\""),
            "the prefix has to be declared or the file is not XML at all")
    }

    // --- helpers --------------------------------------------------------------------------

    private fun get(path: String): String =
        given().get(path).then().statusCode(200).extract().body().asString()

    /** The single JSON-LD node on a page, or the first when the page carries an @graph. */
    private fun jsonLdOf(html: String): JsonElement {
        val nodes = jsonLdNodes(html)
        assertFalse(nodes.isEmpty(), "the page carries no JSON-LD at all")
        return nodes[0]
    }

    /**
     * Every JSON-LD node on the page, *parsed*.
     *
     * Parsing rather than string-matching is the point: the failure this markup is most
     * likely to have is a title that breaks the JSON, and a substring assertion would pass
     * straight through it.
     */
    private fun jsonLdNodes(html: String): List<JsonElement> {
        val nodes = ArrayList<JsonElement>()
        val open = "<script type=\"application/ld+json\">"
        var from = html.indexOf(open)
        while (from >= 0) {
            val start = from + open.length
            val end = html.indexOf("</script>", start)
            assertTrue(end > start, "unterminated ld+json block")
            val parsed = try {
                Json.parseToJsonElement(html.substring(start, end))
            } catch (e: Exception) {
                throw AssertionError("the JSON-LD block is not valid JSON: " + html.substring(start, end), e)
            }
            // A page with several entities emits one document with an @graph rather than a
            // bare array — see JsonLd.renderGraph for why the array form is subtly wrong.
            val graph = parsed.path("@graph")
            if (graph is JsonArray) nodes.addAll(graph) else nodes.add(parsed)
            from = html.indexOf(open, end)
        }
        return nodes
    }

    companion object {
        const val ORIGIN = "https://cinema.example:8443"

        /** Every test runs under the one configured tenant, so names accumulate across methods. */
        private fun unique(name: String): String = name + " " + UUID.randomUUID()

        private fun firstOfType(nodes: List<JsonElement>, type: String): JsonElement? =
            nodes.firstOrNull { typesOf(it).contains(type) }

        private fun typesOf(node: JsonElement): List<String> =
            when (val type = node.path("@type")) {
                is JsonArray -> type.map { it.text() }
                null -> listOf()
                else -> listOf(type.text())
            }

        /** Jackson's `path(key)`, with absent as null where Jackson had its missing node. */
        private fun JsonElement?.path(key: String): JsonElement? = (this as? JsonObject)?.get(key)

        /** Jackson's `asText()` on a node that may be missing: "" for absent, null or a container. */
        private fun JsonElement?.text(): String =
            if (this is JsonPrimitive && this !is JsonNull) content else ""
    }
}
