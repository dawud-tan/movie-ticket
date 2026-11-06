package com.protek.booking.i18n

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the two languages actually put on the page, for the three things most likely to be got
 * wrong while converting a template — none of which [LocalizedMarkupTest] would catch,
 * because that one reads the `<head>`.
 *
 * 1. **A status badge is two uses of one column value.** The CSS class must stay the literal
 *    value the schema writes; only the label is translated. Translating both un-styles every badge
 *    on the page, and it does it silently — the markup is still valid and the page still renders.
 *    See [StatusLabels].
 * 2. **A message carrying markup has to be rendered with `.raw`.** A bundle method
 *    returns an ordinary `String`, so Qute's `HtmlEscaper` treats it like any other
 *    expression result: without `.raw` the `<strong>` in `Msg.pushIosHint` is
 *    printed to the customer as visible angle brackets. Two messages in the bundle carry markup and
 *    this is the cheaper of the two to reach — the other is behind a staff login.
 * 3. **The body follows the URL, not just the header.** A page can pass every SEO assertion
 *    while its copy is still English, because `base.html` alone supplies a translated word to
 *    every page in the tree.
 *
 * No configured public origin here, unlike `LocalizedMarkupTest`: nothing below reads a
 * canonical or an `hreflang`, and the copy does not depend on one.
 */
@QuarkusTest
class LocalizedCopyTest {

    companion object {
        private fun get(path: String): String =
            given().get(path).then().statusCode(200).extract().body().asString()
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    @Test
    fun aStatusBadgeTranslatesItsLabelAndKeepsTheClassTheStylesheetSelectsOn() {
        val hall = aHallWithAScheduledShowing()

        val english = get("/halls/" + hall.resourcePoolId)
        val indonesian = get("/id/halls/" + hall.resourcePoolId)

        assertTrue(english.contains("<span class=\"badge scheduled\">Scheduled</span>"), english)
        assertTrue(indonesian.contains("<span class=\"badge scheduled\">Terjadwal</span>"),
            "the label is translated")
        assertFalse(indonesian.contains("class=\"badge Terjadwal\""),
            "and the class is not — app.css selects on the raw column value")
    }

    @Test
    fun aMessageThatCarriesMarkupRendersAsMarkupRatherThanAsVisibleTags() {
        // The iOS install hint is hidden until seatmap-push.js decides the visitor is on iOS, so
        // a customer would only ever meet this failure on the one device that cannot be tested
        // from here. That is exactly why it is asserted in the suite instead.
        val showing = aShowing()
        val page = get("/halls/" + showing.resourcePoolId +
            "/showings/" + showing.resourcePoolSessionId)

        assertTrue(page.contains("tap <strong>Share</strong> then"), page)
        assertFalse(page.contains("&lt;strong&gt;"),
            "without .raw the tags reach the customer as text")
    }

    @Test
    fun theSeatMapsOwnCopyIsIndonesianOnAPrefixedUrl() {
        // The seat map is the copy-densest page in customer/ and the last one converted. Its
        // legend names the same three states the grid renders as CSS classes.
        val showing = aShowing()
        val page = get("/id/halls/" + showing.resourcePoolId +
            "/showings/" + showing.resourcePoolSessionId)

        assertTrue(page.contains("Tersedia"), "the legend")
        assertTrue(page.contains("Tahan kursi yang dipilih"), "the submit button")
        assertTrue(page.contains("Nama Anda"), "the name field's label")
    }

    @Test
    fun theSeatMapsPollingUrlsCarryThePrefixSoAFragmentCannotArriveInTheOtherLanguage() {
        // Every one of those three endpoints answers with this same fragment. Fetched bare they
        // fall through to the cookie and then to Accept-Language — which in this market is very
        // commonly en-US whoever is holding the phone — so a visitor who arrived on /id from a
        // pasted link, holding no cookie, would watch the page turn English three seconds after
        // it loaded. seatmapFragment.html has the argument.
        val showing = aShowing()
        val path = "/halls/" + showing.resourcePoolId +
            "/showings/" + showing.resourcePoolSessionId
        val page = get("/id" + path)

        assertTrue(page.contains("hx-get=\"/id" + path + "/fragment\""), page)
        assertTrue(page.contains("hx-post=\"/id" + path + "/hold\""), "the hold re-renders it too")
    }

    private fun aHallWithAScheduledShowing(): ResourcePool =
        aShowingIn(fixture.pool(currentTenant.get())).hall

    private fun aShowing(): ResourcePoolSession =
        aShowingIn(fixture.pool(currentTenant.get())).session

    private data class Showing(val hall: ResourcePool, val session: ResourcePoolSession)

    private fun aShowingIn(hall: ResourcePool): Showing {
        val tenant = currentTenant.get()
        fixture.unit(tenant, hall, "A1", "A", 1)
        return Showing(hall, fixture.session(tenant, hall, "scheduled"))
    }
}
