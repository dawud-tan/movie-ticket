package com.protek.booking.i18n

import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The staff side in two languages — `admin/`, the door, and the hold refusals.
 *
 * **Why this is a second class rather than more tests in [LocalizedCopyTest].** That
 * one reads public pages, which carry the language in the URL. Nothing here does: `/admin`
 * and `/orders` keep their bare spelling because `robots.txt` disallows them by
 * exactly that prefix, so `/id/admin` would be a second spelling that list does not cover
 * — `LanguagePreference.path` has the rule. What puts a staff page in Indonesian is
 * therefore the **cookie**, and a test that cannot set one cannot see any of this. Every
 * assertion below sends `booking_lang` by hand for that reason, and it is the first test
 * in the tree to do so.
 *
 * **The three failure shapes it is aimed at**, all of which ship a page that renders:
 * 1. **A half-converted page.** `base.html` supplies a translated word to every page
 *    in the tree, so a staff page can look converted in a screenshot of its header while its whole
 *    body is English. Each assertion below therefore names copy from the body.
 * 2. **Copy that a bundle cannot reach, left behind.** The door's headline and its refusal
 *    sentences come out of `AdmissionService`, and the seat map's refusals out of
 *    `BookingService`. Both used to be string literals next to the code that chose them,
 *    which is the one place a template-by-template conversion never looks.
 * 3. **A CSS hook translated along with the copy beside it.** `Outcome.severity` and a
 *    status badge's class are values the stylesheet selects on; translating either un-styles the
 *    page silently. Same trap [LocalizedCopyTest] pins for the customer badge.
 */
@QuarkusTest
class LocalizedStaffCopyTest {

    companion object {
        private val PRICE = BigDecimal("50000")

        private fun hasAnIndonesianWeekday(page: String): Boolean =
            containsAny(page, "Sen ", "Sel ", "Rab ", "Kam ", "Jum ", "Sab ", "Min ")

        private fun hasAnEnglishWeekday(page: String): Boolean =
            containsAny(page, "Mon ", "Tue ", "Wed ", "Thu ", "Fri ", "Sat ", "Sun ")

        private fun containsAny(page: String, vararg needles: String): Boolean {
            for (needle in needles) {
                if (page.contains(needle)) {
                    return true
                }
            }
            return false
        }

        private fun get(path: String): String =
            given().get(path).then().statusCode(200).extract().body().asString()

        private fun getAs(languageTag: String, path: String): String =
            given().cookie(LanguagePreference.COOKIE, languageTag)
                .get(path).then().statusCode(200).extract().body().asString()
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant

    // --- admin/ templates ----------------------------------------------------------------

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theTenantAdminIndexIsIndonesianForACookieHolderAndEnglishWithout() {
        val indonesian = getAs("id", "/admin")
        val english = get("/admin")

        // Body copy, not the nav: the nav is base.html's and was translated with customer/.
        assertTrue(indonesian.contains("Kalender harga"), indonesian)
        assertTrue(indonesian.contains("Tambah cabang"), "the branch form's heading")
        assertTrue(english.contains("Pricing calendar"), english)
        assertFalse(english.contains("Kalender harga"),
            "no cookie means English — the page did not simply become Indonesian for everyone")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theLongestParagraphOnTheHallPageRendersItsEmphasisRatherThanPrintingTheTags() {
        // admSeatFormsIntro is the longest message in the bundle and the one a tenant reads
        // before the only irreversible-looking thing on that page. It carries four <strong>/<em>
        // spans, so it is rendered .raw — and .raw is exactly what gets forgotten when a
        // paragraph is moved into a bundle.
        val hall = fixture.pool(currentTenant.get())

        val page = getAs("id", "/admin/halls/" + hall.resourcePoolId)

        assertTrue(page.contains("<strong>Menambah kursi</strong>"), page)
        assertTrue(page.contains("<em>ganti seluruh denah</em>"), "the second emphasis")
        assertFalse(page.contains("&lt;strong&gt;"),
            "without .raw the tags reach the operator as visible text")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aShowingStatusBadgeOnTheStaffSideTranslatesItsLabelAndKeepsItsClass() {
        val showing = aShowing("scheduled")

        val page = getAs("id",
            "/admin/showings/" + showing.resourcePoolSessionId + "/reservations")

        assertTrue(page.contains("<span class=\"badge scheduled\">Terjadwal</span>"), page)
        assertFalse(page.contains("class=\"badge Terjadwal\""),
            "app.css selects on the raw column value, here as much as on the customer side")
    }

    // --- the door: copy that lives in AdmissionService ------------------------------------

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anUnknownCodeAtTheDoorIsRefusedInIndonesianWithTheSeverityClassUntouched() {
        val showing = aShowing("on_sale")

        val panel = given().cookie(LanguagePreference.COOKIE, "id")
            .formParam("admissionToken", "NOSUCHTOKEN")
            .`when`().post("/admin/showings/" + showing.resourcePoolSessionId + "/admission")
            .then().statusCode(200).extract().body().asString()

        assertTrue(panel.contains("Kode tidak dikenal"), "the headline, from Outcome.headline")
        assertTrue(panel.contains("Kode itu bukan tiket bioskop ini."), "and the message under it")
        // The colour is a CSS class and stays ok/warn/bad in every language — the same split
        // StatusLabels makes, and the reason headline() left the enum while severity() did not.
        assertTrue(panel.contains("scan-result bad"), panel)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theSameRefusalIsEnglishWithoutTheCookie() {
        val showing = aShowing("on_sale")

        val panel = given()
            .formParam("admissionToken", "NOSUCHTOKEN")
            .`when`().post("/admin/showings/" + showing.resourcePoolSessionId + "/admission")
            .then().statusCode(200).extract().body().asString()

        assertTrue(panel.contains("Unknown code"), panel)
        assertFalse(panel.contains("Kode tidak dikenal"), "not translated for everybody")
    }

    // --- the seat map's error banner: copy that lives in BookingService --------------------

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aHoldRefusalRendersInTheLanguageOfThePageThatAsksForIt() {
        // The refusal a customer meets most often, and the one singled out first: it
        // renders in the seat map's .error-banner, on a page that is otherwise entirely
        // Indonesian. Driven through HTTP rather than through the service directly, because
        // what is being checked is precisely that the request's language reaches it — and the
        // hold endpoint is @RolesAllowed, so it needs an identity to reach at all.
        val showing = aSellableShowing()
        val path = "/halls/" + showing.resourcePoolId +
            "/showings/" + showing.resourcePoolSessionId + "/hold"

        val indonesian = given().cookie(LanguagePreference.COOKIE, "id")
            .formParam("customerName", "Budi").formParam("customerPhone", "+628120000001")
            .`when`().post("/id" + path)
            .then().statusCode(200).extract().body().asString()
        val english = given()
            .formParam("customerName", "Budi").formParam("customerPhone", "+628120000001")
            .`when`().post(path)
            .then().statusCode(200).extract().body().asString()

        assertTrue(indonesian.contains("Pilih setidaknya satu kursi."), indonesian)
        assertTrue(english.contains("Select at least one seat."), english)
    }

    // --- dates ----------------------------------------------------------------------------

    @Test
    fun anOnScreenDateFollowsTheReader() {
        // Every weekday abbreviation differs between the two locales and four of twelve month
        // names do, so a seat map that is Indonesian down to the seat legend and then says
        // "Tue 21 Apr" is the same kind of unfinished as an English error banner on it.
        val showing = aShowing("on_sale")
        val path = "/halls/" + showing.resourcePoolId +
            "/showings/" + showing.resourcePoolSessionId

        val indonesian = getAs("id", "/id" + path)
        val english = get(path)

        assertTrue(hasAnIndonesianWeekday(indonesian),
            "no Indonesian weekday abbreviation on an Indonesian seat map: " + indonesian)
        assertTrue(hasAnEnglishWeekday(english),
            "and the English page still reads in English: " + english)
    }

    // --- fixtures --------------------------------------------------------------------------

    private fun aShowing(status: String): ResourcePoolSession {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        fixture.unit(tenant, hall, "A1", "A", 1)
        return fixture.session(tenant, hall, status)
    }

    private fun aSellableShowing(): ResourcePoolSession {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        fixture.unit(tenant, hall, "A1", "A", 1)
        fixture.rateRule(tenant, hall, PRICE)
        return fixture.session(tenant, hall, "on_sale")
    }
}
