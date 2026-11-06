package com.protek.booking.service

import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Calendar pricing, end to end: the band a showing sells at, and the two ways
 * of getting it wrong that its design warned about before a line of it was written.
 *
 * **Fixed dates in 2099, not dates relative to today.** Every assertion here is about a
 * day of the week, so a suite that computed "next Sunday" would be asserting different things
 * on different days and would eventually fail on one of them for a reason nobody could
 * reproduce. Monday 1 June 2099 anchors a real week: 1 Mon, 2 Tue, … 7 Sun.
 *
 * Holidays are added on dates in weeks no other test method uses, because the list is
 * tenant-scoped and these methods share a tenant — a stray holiday would silently turn another
 * method's Tuesday into a top-band day.
 */
@QuarkusTest
class CalendarPricingTest {

    companion object {
        private val WIB: ZoneId = ZoneId.of("Asia/Jakarta")

        /** Monday 1 June 2099. The week 1-7 June is the plain day-of-week week; no holiday is ever
         * added inside it. */
        private val MONDAY: LocalDate = LocalDate.of(2099, 6, 1)

        /** A typical published list in this market: 25k / 30k / 35k, holiday at the weekend rate.
         * Deliberately realistic numbers — a test that priced 1/2/3 would not notice a band swap. */
        private val MON_WED = BigDecimal("25000")
        private val THU_FRI = BigDecimal("30000")
        private val SAT_SUN = BigDecimal("35000")
        private val HOLIDAY = BigDecimal("40000")

        /**
         * A distinct phone number per hold.
         *
         * Not cosmetic. `@TestSecurity` bypasses the identity augmentor, so
         * a customer-role hold here has no linked customer row and no email either — every one of
         * them inserts a fresh `customer`. Reuse one number across the class and the first
         * staff-role hold, which resolves its customer *by phone*, finds four rows and dies
         * in `queryOne`. The failure names the query rather than the cause, so it is worth
         * one line to avoid.
         */
        private val PHONE_SEQ = AtomicInteger()

        /** An instant from a local date and a wall-clock time in Jakarta — how a cinema thinks
         * about a showtime, and the conversion the production code has to get right too. */
        private fun at(date: LocalDate, hour: Int, minute: Int): Instant =
            ZonedDateTime.of(date, LocalTime.of(hour, minute), WIB).toInstant()
    }

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var fixture: TestFixture

    // --- The three weekday bands ----------------------------------------------------------

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aMondayShowingSellsAtTheMonWedBand() {
        assertPricedAt(MON_WED, at(MONDAY, 19, 0), null)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aThursdayShowingSellsAtTheThuFriBand() {
        assertPricedAt(THU_FRI, at(MONDAY.plusDays(3), 19, 0), null)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aSaturdayShowingSellsAtTheSatSunBand() {
        assertPricedAt(SAT_SUN, at(MONDAY.plusDays(5), 19, 0), null)
    }

    // --- The two traps named before this was built ------------------------------------------

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun anEarlyMondayShowingIsStillMonday_notSundayInUtc() {
        // The first trap, with the arithmetic the right way round. Every Indonesian zone
        // is AHEAD of UTC, so it is the FIRST hours of a local day that land in the previous
        // UTC day: 00:30 on Monday in Jakarta is 17:30 on Sunday in UTC. A band computed from
        // the raw Instant sells that midnight show at the weekend price -- a whole house
        // overcharged, with nothing anywhere reporting an error.
        //
        // Worth stating because the example is easy to give inverted ("a 23:30 WIB Sunday
        // showing reads as Monday in UTC"). It does not: 23:30 WIB is 16:30 UTC the same day.
        // The trap is real, the direction was not, and only one of the two cases actually
        // breaks -- so a test written from the inverted wording would have passed against a UTC
        // implementation and proved nothing.
        val earlyMonday = at(MONDAY, 0, 30)
        assertEquals(DayOfWeek.SUNDAY, earlyMonday.atZone(ZoneId.of("UTC")).dayOfWeek,
            "this instant must be a Sunday in UTC, or the test is not testing the trap")

        assertPricedAt(MON_WED, earlyMonday, null)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun theDayOfWeekComesFromTheBranchTimezone_notTheHallsUntouchedDefault() {
        // One instant, two branches, two bands -- which is the whole reason the site table has a timezone
        // on the branch. 23:30 on Sunday in Jakarta is 01:30 on Monday in Jayapura, so the
        // same screening is a weekend ticket on Java and a Mon-Wed ticket in Papua.
        //
        // The hall row says 'Asia/Jakarta' in BOTH cases, because resource_pool.timezone is
        // NOT NULL DEFAULT 'Asia/Jakarta' (V1) and nobody edits it. That is why the branch has
        // to win when there is one: "the hall overrides" and "the hall was never touched" are
        // the same bytes, so reading the hall first would mispriced every branch east of Java
        // while looking exactly like a deliberate per-hall setting.
        val lateSunday = at(MONDAY.plusDays(6), 23, 30)

        assertPricedAt(SAT_SUN, lateSunday, null)

        val papua = fixture.siteInZone(currentTenant.get(), "Demo Cinema Jayapura", "Jayapura", "Asia/Jayapura")
        assertPricedAt(MON_WED, lateSunday, papua)
    }

    // --- The holiday list -------------------------------------------------------------------

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aListedHolidayOverridesTheWeekdayBand() {
        // Tuesday 16 June 2099 -- an ordinary Mon-Wed day until it is on the list.
        val tuesday = LocalDate.of(2099, 6, 16)
        fixture.holiday(currentTenant.get(), tuesday, "Hari Raya Idul Fitri")

        assertPricedAt(HOLIDAY, at(tuesday, 19, 0), null)
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun theDayBeforeAListedHolidaySellsAtTheHolidayBand() {
        // The clause that is the entire reason the table exists: no date arithmetic yields
        // "the day before a public holiday", so Wednesday 24 June is an ordinary Mon-Wed day
        // until Thursday 25 June is listed -- at which point it is a top-band day.
        val thursdayHoliday = LocalDate.of(2099, 6, 25)
        val eve = thursdayHoliday.minusDays(1)
        fixture.holiday(currentTenant.get(), thursdayHoliday, "Kenaikan Isa Almasih")

        assertPricedAt(HOLIDAY, at(eve, 19, 0), null)
    }

    @Test
    fun theSeatMapExplainsWhyAHolidayCostsMore() {
        // Staff get asked this at the counter, so the page has to answer it rather than showing
        // a number that moved. Named after the holiday itself on the day, and as "the day
        // before" on its eve -- showing "the day before Idul Fitri" on Idul Fitri would read as
        // a bug even though the band is identical.
        val wednesdayHoliday = LocalDate.of(2099, 7, 8)
        fixture.holiday(currentTenant.get(), wednesdayHoliday, "Tahun Baru Islam")
        val hall = calendarHall(null, at(wednesdayHoliday, 19, 0))

        val onTheDay: CalendarPricing.SeatPrice = catalogService
            .getPriceDisplay(hall.pool.resourcePoolId, hall.showing.startsAt!!).orElseThrow()
        assertEquals("Public holiday — Tahun Baru Islam", onTheDay.bandLabel())

        val eveHall = calendarHall(null, at(wednesdayHoliday.minusDays(1), 19, 0))
        val onTheEve: CalendarPricing.SeatPrice = catalogService
            .getPriceDisplay(eveHall.pool.resourcePoolId, eveHall.showing.startsAt!!).orElseThrow()
        assertEquals("Public holiday — the day before Tahun Baru Islam", onTheEve.bandLabel())
    }

    @Test
    // Staff, not a customer, and only because of a test-harness limit rather than anything about
    // the feature: this method reads the booking back through getGroupDetail, whose ownership
    // check compares CurrentActor.customerId() -- an attribute the SecurityIdentityAugmentor sets
    // and @TestSecurity bypasses entirely. Pricing does not depend on who is buying.
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun addingAHolidayLaterDoesNotRestateWhatAnEarlierBookingCost() {
        // reservation.price_amount is pinned at hold time (the refund obligation rests on this),
        // and calendar pricing gives it more to be pinned against: a date added to the list in
        // March must not turn a February ticket into a top-band one retrospectively. New holds see
        // the new list.
        val friday = LocalDate.of(2099, 7, 17)
        val hall = calendarHall(null, at(friday, 19, 0))

        val before = holdOne(hall, 0)
        assertEquals(0, THU_FRI.compareTo(before.priceAmount!!), "sold as an ordinary Friday")

        fixture.holiday(currentTenant.get(), friday.plusDays(1), "Hari Raya Idul Adha")

        val after = holdOne(hall, 1)
        assertEquals(0, HOLIDAY.compareTo(after.priceAmount!!),
            "the next sale is a holiday eve, because the list is live")
        val reread: Reservation = bookingService.getGroupDetail(before.reservationGroupId)
            .lines!![0].reservation!!
        assertEquals(0, THU_FRI.compareTo(reread.priceAmount!!),
            "the earlier booking must still say what it was actually sold for")
    }

    // --- What did not change ----------------------------------------------------------------

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun aFlatRuleIsPricedExactlyAsItWasBeforeA6() {
        // Every tenant that predates calendar pricing is on the flat shape. Whatever calendar pricing does, a flat
        // rule must keep charging one number on every day of the week -- including on a day
        // that is on the holiday list, which is where a careless implementation would start
        // reaching for a band that a flat rule does not have.
        val tenant: Tenant = currentTenant.get()
        val sunday = LocalDate.of(2099, 8, 2)
        fixture.holiday(tenant, sunday, "Hari Kemerdekaan (test)")
        val pool: ResourcePool = fixture.pool(tenant)
        val seat: ResourceUnit = fixture.unit(tenant, pool, "F1", "F", 1)
        val showing: ResourcePoolSession = fixture.sessionAt(tenant, pool, at(sunday, 19, 0), "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val result = bookingService.hold(pool.resourcePoolId,
            showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId),
            "Flat Customer", "+628110009" + PHONE_SEQ.incrementAndGet(),
            AgeClassification.Attestation.NOT_TICKED)

        assertTrue(result.success, result.failureReason)
        assertEquals(0, BigDecimal("50000").compareTo(result.reservations[0].priceAmount!!))
    }

    // --- The two things that ride along with the rule ----------------------------------------

    @Test
    // Staff, not a customer, and only because of a test-harness limit rather than anything about
    // the feature: this method reads the booking back through getGroupDetail, whose ownership
    // check compares CurrentActor.customerId() -- an attribute the SecurityIdentityAugmentor sets
    // and @TestSecurity bypasses entirely. Pricing does not depend on who is buying.
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun whatTheTicketIncludesIsReadFromTheRuleThatWasInForceWhenItWasBought() {
        // A published price list may bundle a fixed drink voucher into every band. Not an ordering
        // system -- one line on the rule that prints on the ticket. It has to be read from the
        // rule the booking was taken under, or a repriced hall restates last month's tickets.
        val hall = calendarHall(null, at(MONDAY, 19, 0), java.util.List.of("Termasuk voucher minuman Rp 10.000"))

        val held = holdOne(hall, 0)

        assertEquals(java.util.List.of("Termasuk voucher minuman Rp 10.000"),
            bookingService.getGroupDetail(held.reservationGroupId).inclusions)

        // Reprice the hall with a different voucher. The existing ticket must not change.
        catalogService.setRate(hall.pool.resourcePoolId,
            RatePlan.flat(BigDecimal("25000"), java.util.List.of("Termasuk popcorn")), false)
        assertEquals(java.util.List.of("Termasuk voucher minuman Rp 10.000"),
            bookingService.getGroupDetail(held.reservationGroupId).inclusions,
            "a ticket must keep promising what it promised when it was sold")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun applyingARateToTheBranchPricesEveryHallInIt() {
        // A.7b obs. 5's decision, which was to keep rate_rule keyed on the hall and add a bulk
        // write rather than re-key it to the branch. This is what makes that decision usable:
        // without it, a 6-screen multiplex is 24 numbers typed by hand every time prices move.
        val tenant: Tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Rekaan", "Rekaan")
        val studio1: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 1")
        val studio2: ResourcePool = fixture.poolAtSite(tenant, site, "Studio 2")
        val elsewhere: ResourcePool = fixture.pool(tenant)
        fixture.rateRule(tenant, elsewhere, BigDecimal("50000"))

        val written = catalogService.setRate(studio1.resourcePoolId,
            RatePlan.flat(BigDecimal("27000"), java.util.List.of()), true)

        assertEquals(2, written, "both studios in the branch")
        assertEquals(0, BigDecimal("27000").compareTo(
                catalogService.getRatePlan(studio2.resourcePoolId).orElseThrow().flatAmount()!!),
            "the sibling studio must be repriced too")
        assertEquals(0, BigDecimal("50000").compareTo(
                catalogService.getRatePlan(elsewhere.resourcePoolId).orElseThrow().flatAmount()!!),
            "a hall in another branch must not be touched")
    }

    // --- scaffolding -------------------------------------------------------------------------

    private data class Hall(val pool: ResourcePool, val showing: ResourcePoolSession, val seats: List<ResourceUnit>)

    private fun calendarHall(site: Site?, startsAt: Instant): Hall =
        calendarHall(site, startsAt, java.util.List.of())

    /** A hall on that published band structure, with a showing at a given instant. Two seats,
     * so a test can hold twice without the second hold hitting the EXCLUDE constraint. */
    private fun calendarHall(site: Site?, startsAt: Instant, inclusions: List<String>): Hall {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = if (site == null)
            fixture.pool(tenant)
        else
            fixture.poolAtSite(tenant, site, "Studio " + UUID.randomUUID())
        val seats: List<ResourceUnit> = java.util.List.of(
            fixture.unit(tenant, pool, "C1", "C", 1),
            fixture.unit(tenant, pool, "C2", "C", 2))
        val showing: ResourcePoolSession = fixture.sessionAt(tenant, pool, startsAt, "on_sale")
        fixture.calendarRateRule(tenant, pool, MON_WED, THU_FRI, SAT_SUN, HOLIDAY, inclusions)
        return Hall(pool, showing, seats)
    }

    private fun holdOne(hall: Hall, seatIndex: Int): Reservation {
        val result = bookingService.hold(hall.pool.resourcePoolId,
            hall.showing.resourcePoolSessionId,
            java.util.List.of(hall.seats[seatIndex].resourceUnitId),
            "Calendar Customer", "+628110009" + PHONE_SEQ.incrementAndGet(),
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(result.success, result.failureReason)
        return result.reservations[0]
    }

    /** Sells one seat of a showing starting at `startsAt` and asserts what it cost. */
    private fun assertPricedAt(expected: BigDecimal, startsAt: Instant, site: Site?) {
        val hall = calendarHall(site, startsAt)

        val held = holdOne(hall, 0)

        assertEquals(0, expected.compareTo(held.priceAmount!!),
            "showing starting " + startsAt.atZone(WIB) + " (WIB) priced at " + held.priceAmount)
    }
}
