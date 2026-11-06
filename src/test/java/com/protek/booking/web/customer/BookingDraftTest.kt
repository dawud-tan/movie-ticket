package com.protek.booking.web.customer

import com.protek.booking.domain.AppUser
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.BookingDraftService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * `/booking?booking_ref=…` — the seat-selection page a showtime leads to.
 *
 * **The two halves worth pinning down.** First, that pressing a showtime while signed out
 * shows a sign-in sheet rather than doing nothing — which is a status-code question, not a
 * markup one, because htmx will not swap a non-2xx response. Second, that the
 * booking reference survives the date and time fields: that is the entire reason a draft exists
 * rather than the showing id simply being in the URL.
 */
@QuarkusTest
class BookingDraftTest {

    companion object {
        private val JAKARTA: ZoneId = ZoneId.of("Asia/Jakarta")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingDrafts: BookingDraftService

    private data class Scheduled(val site: Site, val hall: ResourcePool, val film: Film,
                                 val showings: List<ResourcePoolSession>)

    /**
     * One film at one branch, playing twice today and once tomorrow — the smallest schedule
     * that makes both fields meaningful: a date field with two options and a time field with
     * two on the selected date.
     */
    private fun seedSchedule(): Scheduled {
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Rekaan", "Rekaan-" + UUID.randomUUID())
        val hall: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 2")
        val film: Film = fixture.film(tenant, "Draft Film " + UUID.randomUUID(), "SU")
        // Anchored to noon tomorrow in the branch's own zone, so "today" and "tomorrow" are two
        // calendar days there however close to midnight the suite happens to run.
        val noon: Instant = LocalDate.now(JAKARTA).plusDays(1).atTime(12, 0).atZone(JAKARTA).toInstant()
        return Scheduled(site, hall, film, java.util.List.of(
            fixture.sessionOfFilmAt(tenant, hall, film, noon, "on_sale"),
            fixture.sessionOfFilmAt(tenant, hall, film, noon.plus(Duration.ofHours(3)), "on_sale"),
            fixture.sessionOfFilmAt(tenant, hall, film, noon.plus(Duration.ofDays(1)), "on_sale")))
    }

    private fun signedIn(tenant: Tenant): AppUser {
        val user: AppUser = fixture.appUser(tenant, "draft-" + UUID.randomUUID() + "@example.test",
            AppUser.ROLE_CUSTOMER)
        QuarkusMock.installMockForType(ActingAs.customer(null, user.appUserId), CurrentActor::class.java)
        return user
    }

    @Test
    fun anAnonymousVisitorPressingAShowtimeGetsTheSignInSheetAndNotADeadButton() {
        val scheduled = seedSchedule()

        val body = given()
            .formParam("sessionId", scheduled.showings[0].resourcePoolSessionId.toString())
            .header("HX-Request", "true")
            .`when`().post("/booking")
            // 200, deliberately. A 401 is what @RolesAllowed would produce and htmx does not
            // swap one, so the button would silently do nothing — the dead-button
            // failure, which this application has already paid for once.
            .then().statusCode(200)
            .extract().asString()

        assertTrue(body.contains("Sign in to pick your seats"), body)
        assertTrue(body.contains("bottom-sheet"), "steps 2e/3e call for a sheet, not a page")
        // The href has to come from SignInLink and never from a literal "/login": followed from
        // the http:8080 entry point, a relative /login asks Google for
        // redirect_uri=https://host:8080/callback — right scheme, wrong port, nothing listening
        // for TLS there. Asserted against the TEMPLATE rather than the rendered output, exactly
        // as src/test/js step 0 does, because with no PUBLIC_HTTPS_BASE_URL configured — which
        // is every test run and every dev session — SignInLink correctly renders "/login" and a
        // check on the output would pass whether the injection was there or not.
        val template = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/customer/authSheet.html"))
        assertTrue(template.contains("{inject:signIn.url}"), template)
        // Qute comments stripped first: this file explains the trap at length, and the
        // explanation naturally quotes the very string being forbidden. A Regex, because Java's
        // replaceAll takes one and Kotlin's String overload is literal (pattern 7).
        val markup = template.replace(Regex("(?s)\\{!.*?!\\}"), "")
        assertFalse(markup.contains("\"/login\""),
            "a literal /login in the sheet would work in dev and send an http:8080 visitor "
                    + "to a dead port in production")
    }

    @Test
    fun aSignedInCustomerIsSentStraightToTheirBooking() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())

        val redirect = given()
            .formParam("sessionId", scheduled.showings[0].resourcePoolSessionId.toString())
            .header("HX-Request", "true")
            .`when`().post("/booking")
            .then().statusCode(200)
            .extract().header("HX-Redirect")

        assertNotNull(redirect, "htmx needs HX-Redirect to navigate; it never swaps a 3xx")
        assertTrue(redirect.startsWith("/booking?booking_ref="), redirect)
    }

    @Test
    fun withoutHtmxTheSameFormPostGetsAnOrdinaryRedirect() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())

        // The showtime strip is rendered as real forms precisely so the flow works with no
        // JavaScript. Answering only HX-Redirect would leave that path on a blank page.
        given().redirects().follow(false)
            .formParam("sessionId", scheduled.showings[0].resourcePoolSessionId.toString())
            .`when`().post("/booking")
            .then().statusCode(303)
            .header("Location", containsString("/booking?booking_ref="))
    }

    @Test
    fun pressingTheSameShowtimeTwiceReusesOneBookingReference() {
        val scheduled = seedSchedule()
        val user = signedIn(currentTenant.get())

        val first = bookingDrafts.openDraft(scheduled.showings[0].resourcePoolSessionId)
            .orElseThrow()
        val second = bookingDrafts.openDraft(scheduled.showings[0].resourcePoolSessionId)
            .orElseThrow()

        // A double tap on a phone sends two POSTs milliseconds apart. Two refs for one intention
        // would leave the second page load looking at a different booking from the first tab.
        assertEquals(first, second)
        assertNotNull(user.appUserId)
    }

    @Test
    @TestSecurity(user = "booker@example.test", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "booker@example.test")])
    fun theBookingPageStatesTheDateTheTimeTheFilmTheCinemaAndTheStudio() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val bookingRef = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        val page = given().`when`().get("/booking?booking_ref=" + bookingRef)
            .then().statusCode(200).extract().asString()

        assertTrue(page.contains(scheduled.film.title!!), "movie title")
        assertTrue(page.contains(scheduled.site.name!!), "movie theater name")
        assertTrue(page.contains("Studio 2"),
            "studio number, parsed off the hall name — resource_pool has no number column")
        assertTrue(page.contains("id=\"booking-date\""), "date field")
        assertTrue(page.contains("id=\"booking-time\""), "time field")
        assertTrue(page.contains("id=\"seat-grid\""), "the seat map itself")
    }

    @Test
    fun bothOfTodaysShowtimesAreOfferedInTheTimeFieldAndBothDaysInTheDateField() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val bookingRef = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        val page: BookingDraftService.BookingPage = bookingDrafts.load(bookingRef)

        assertEquals(2, page.dates.size, "two calendar days in the branch's zone")
        assertEquals(2, page.timesOnSelectedDate().size,
            "the time field lists only the selected day's showings, not the whole schedule")
        assertTrue(page.dates[0].selected)
    }

    @Test
    fun changingTheTimeKeepsTheSameBookingReference() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val bookingRef = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        val moved: BookingDraftService.BookingPage = bookingDrafts.moveToShowing(
            bookingRef, scheduled.showings[1].resourcePoolSessionId)

        // The whole reason the draft exists. With the session id in the URL instead, changing
        // the time would change the reference, and the customer's own address bar would tell
        // them they had started over.
        assertEquals(bookingRef, moved.draft.bookingRef)
        assertEquals(scheduled.showings[1].resourcePoolSessionId,
            moved.showing().resourcePoolSessionId)
    }

    @Test
    fun changingTheDateLandsOnTheEarliestShowtimeThatDay() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val bookingRef = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        val tomorrow: LocalDate = scheduled.showings[2].startsAt!!.atZone(JAKARTA).toLocalDate()
        val moved: BookingDraftService.BookingPage = bookingDrafts.moveToDate(bookingRef, tomorrow)

        // A date alone does not identify a showing, so something has to choose. The earliest is
        // the only stable answer — "the same time of day" is undefined when the new date has no
        // 19:00.
        assertEquals(scheduled.showings[2].resourcePoolSessionId,
            moved.showing().resourcePoolSessionId)
        assertEquals(bookingRef, moved.draft.bookingRef)
    }

    @Test
    @TestSecurity(user = "booker@example.test", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "booker@example.test")])
    fun choosingATimeAnotherOfTheCustomersBookingsIsAlreadyOnGoesToThatBooking() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val first = bookingDrafts.openDraft(scheduled.showings[0].resourcePoolSessionId).orElseThrow()
        // A second tab, or the same film's later showtime pressed from the cinema page: booking_draft keeps
        // one draft per account and showing, so this one already owns the time about to be chosen.
        val second = bookingDrafts.openDraft(scheduled.showings[1].resourcePoolSessionId).orElseThrow()

        val location = given().redirects().follow(false)
            .formParam("showtime", scheduled.showings[1].resourcePoolSessionId.toString())
            .`when`().post("/booking/$first/showtime")
            .then().statusCode(303).extract().header("Location")

        assertTrue(location.endsWith("/booking?booking_ref=$second"), location)
        // Nothing merged: a tab still open on the first booking keeps the showing it was on.
        assertEquals(scheduled.showings[0].resourcePoolSessionId,
            bookingDrafts.load(first).showing().resourcePoolSessionId)
    }

    @Test
    fun aDateWhoseEarliestShowtimeIsAlreadyOpenLandsOnThatBooking() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val first = bookingDrafts.openDraft(scheduled.showings[0].resourcePoolSessionId).orElseThrow()
        val tomorrows = bookingDrafts.openDraft(scheduled.showings[2].resourcePoolSessionId).orElseThrow()

        val moved = bookingDrafts.moveToDate(first, scheduled.showings[2].startsAt!!.atZone(JAKARTA).toLocalDate())

        assertEquals(tomorrows, moved.draft.bookingRef)
        assertEquals(scheduled.showings[2].resourcePoolSessionId, moved.showing().resourcePoolSessionId)
    }

    @Test
    fun aDateWithNoShowingsLeavesTheBookingWhereItWas() {
        val scheduled = seedSchedule()
        signedIn(currentTenant.get())
        val bookingRef = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        // Reachable without anybody misbehaving: the field was rendered from a schedule that a
        // cancellation — or the viability rule — may have emptied since.
        val unmoved: BookingDraftService.BookingPage =
            bookingDrafts.moveToDate(bookingRef, LocalDate.now(JAKARTA).plusYears(1))

        assertEquals(scheduled.showings[0].resourcePoolSessionId,
            unmoved.showing().resourcePoolSessionId)
    }

    @Test
    fun oneCustomersBookingReferenceIsNotAnotherCustomersToOpen() {
        val scheduled = seedSchedule()
        val tenant = currentTenant.get()
        signedIn(tenant)
        val theirs = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        // The test that matters most here. RLS isolates tenants and NOT customers within a
        // tenant, so a booking_ref in a query string looks like a capability and is not one —
        // without the ownership check, anyone holding the link sees which film a stranger is
        // buying seats for.
        signedIn(tenant)
        assertThrows(ForbiddenException::class.java) { bookingDrafts.load(theirs) }
    }

    @Test
    fun staffMayOpenACustomersBookingBecauseFinishingOneOnThePhoneIsTheirJob() {
        val scheduled = seedSchedule()
        val tenant = currentTenant.get()
        signedIn(tenant)
        val customerRef = bookingDrafts.openDraft(
            scheduled.showings[0].resourcePoolSessionId).orElseThrow()

        QuarkusMock.installMockForType(ActingAs.staff(), CurrentActor::class.java)

        // Same rule BookingService.assertMayActOn applies: releasing and completing other
        // people's bookings is the third tenant-facing use case.
        assertEquals(customerRef, bookingDrafts.load(customerRef).draft.bookingRef)
    }

    @Test
    fun anAnonymousVisitorCannotOpenTheBookingPageAtAll() {
        val status = given().redirects().follow(false).`when`().get("/booking?booking_ref=" + UUID.randomUUID())
            .then().extract().statusCode()

        // Unlike the POST above, this one IS behind @RolesAllowed: there is no sheet to deliver
        // on a full page load, and the OIDC redirect is the right answer.
        assertTrue(status == 401 || (status >= 300 && status < 400),
            "anonymous GET /booking returned " + status + "; expected 401 or a redirect to the IdP")
    }

    @Test
    fun dismissingTheSheetRemovesIt() {
        // An endpoint that answers with nothing, on purpose: an empty swap into #auth-sheet is
        // what removes the sheet, and it keeps the behaviour in markup rather than in an inline
        // script a content-security policy would forbid.
        given().`when`().get("/booking/sheet/dismiss")
            .then().statusCode(200).body(org.hamcrest.Matchers.emptyOrNullString())
    }
}
