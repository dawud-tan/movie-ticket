package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

/**
 * The *per-day* purchase cap: 10 seats per customer per calendar day, across
 * every showing on it. The sibling of `PurchaseCapTest`'s per-showtime cap, and a
 * different mechanism rather than a wider one — that check compares a list length and touches no
 * database, this one is a count scoped to a customer and a local day.
 *
 * **Fixed dates in 2099, for the reason `CalendarPricingTest` gives**: every
 * assertion here is about which day a showing falls on, so dates computed from today would
 * assert something different every morning. Monday 5 October 2099 anchors a week no other test
 * class puts fixtures in — the calendar tests live in June to August, and a stray booking or
 * holiday in a shared week would move a count this class asserts on.
 *
 * **Every method books as its own identity.** The count is per `customer` row, and
 * these methods share a tenant, so one identity across the class would have each method
 * counting the ones that ran before it. Which knob makes an identity depends on the role, and
 * that is a harness fact rather than a product one: `@TestSecurity` bypasses the identity
 * augmentor, so a customer-role caller has no linked `customer` row and
 * `BookingService` falls back to `booking.email` — the augmentor's real attribute
 * key, *not* the plain `"email"` the other test classes set, which
 * `CurrentActor.email()` does not read and which therefore mints a fresh customer per
 * hold. Staff-role methods have no email path at all and are identified by phone.
 */
@QuarkusTest
class DailyPurchaseCapTest {

    companion object {
        private val WIB: ZoneId = ZoneId.of("Asia/Jakarta")

        /** Monday 5 October 2099. Sunday 4 October is the day before it. */
        private val MONDAY: LocalDate = LocalDate.of(2099, 10, 5)

        /** A showtime from a local date and a wall-clock time — how a cinema states one, and the
         * conversion the cap has to make in the other direction. */
        private fun at(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Instant =
            ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant()
    }

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var purchaseCap: PurchaseCap
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var reservationRepo: ReservationRepository

    // --- Accumulating across showings on one day --------------------------------------------

    @Test
    @TestSecurity(user = "day1@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "day1@demo-cinema.example")])
    fun seatsHeldForAnEarlierShowingCountAgainstALaterOneTheSameDay() {
        // The whole feature in one method: two separate holds, two separate showings, one day.
        // Neither hold breaches the per-showtime cap — this is refused only because of what the
        // customer already has, which is precisely what the per-showtime cap cannot see.
        val perHold = perHold()
        val perDay = perDay()
        val hall = hall(null, perHold)
        val morning = showingAt(hall, at(MONDAY, 10, 0, WIB))
        val evening = showingAt(hall, at(MONDAY, 20, 0, WIB))

        assertTrue(hold(hall, morning, perHold).success, "the first hold is within both caps")

        val asked = perDay - perHold + 1
        val refused = hold(hall, evening, asked)

        assertFalse(refused.success, "the day is full at " + perDay + " seats")
        assertTrue(refused.reservations.isEmpty())
        val why = refused.failureReason!!
        // Naming the day is not decoration. The seats being counted are for a DIFFERENT showing,
        // so a customer looking at an empty grid has no other way to know what this is about.
        assertTrue(why.contains("already have " + perHold + " seats"), why)
        assertTrue(why.contains("showings on Mon 5 Oct"), why)
        assertTrue(why.contains("limit is " + perDay + " a day"), why)
        assertTrue(why.contains("add " + (perDay - perHold) + " more"), why)
        assertTrue(why.contains("untick " + (asked - (perDay - perHold))), why)
        assertNull(refused.failedResourceUnitId,
            "no specific seat blocked this — a seat id would send the customer off to pick a "
                + "different seat, which fixes nothing")
    }

    @Test
    @TestSecurity(user = "day2@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "day2@demo-cinema.example")])
    fun exactlyTheDailyCapSucceeds_andTheNextSeatIsRefusedWithNothingToUntick() {
        // Both boundaries, because they are one off from each other and the first one costs the
        // tenant a sale if it is wrong.
        val perHold = perHold()
        val perDay = perDay()
        val hall = hall(null, perHold)
        val morning = showingAt(hall, at(MONDAY, 10, 0, WIB))
        val evening = showingAt(hall, at(MONDAY, 20, 0, WIB))

        assertTrue(hold(hall, morning, perHold).success)
        val onTheNose = hold(hall, evening, perDay - perHold)
        assertTrue(onTheNose.success, "the cap is inclusive: " + onTheNose.failureReason)
        assertEquals(perDay - perHold, onTheNose.reservations.size)

        // With no headroom left the "untick N" advice would read "untick 1" against a selection
        // of one, i.e. "select nothing". The message has to change shape, not just its numbers.
        val oneTooMany = hold(hall, evening, 1, perDay - perHold)
        assertFalse(oneTooMany.success)
        val why = oneTooMany.failureReason!!
        assertTrue(why.contains("already have " + perDay + " seats"), why)
        assertTrue(why.contains("Release one of those"), why)
        assertFalse(why.contains("untick"), "nothing left to untick: " + why)
    }

    @Test
    @TestSecurity(user = "day3@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "day3@demo-cinema.example")])
    fun seatsForAnotherDayDoNotCount() {
        // The cap is per day, not per customer — a family that goes to the cinema on Monday and
        // again on Tuesday is the ordinary case, not the one being policed.
        val perHold = perHold()
        val hall = hall(null, perHold)
        val monday = showingAt(hall, at(MONDAY, 20, 0, WIB))
        val tuesday = showingAt(hall, at(MONDAY.plusDays(1), 20, 0, WIB))

        assertTrue(hold(hall, monday, perHold).success)
        val nextDay = hold(hall, tuesday, perHold)

        assertTrue(nextDay.success, "a different day starts a fresh allowance: "
            + nextDay.failureReason)
        assertEquals(perHold, nextDay.reservations.size)
    }

    // --- The trap calendar pricing paid for, in a different window---------------------------

    @Test
    @TestSecurity(user = "day4@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "day4@demo-cinema.example")])
    fun theDayIsTheBranchsLocalDay_notTheUtcDayTheTwoShowingsShare() {
        // Calendar pricing's trap, in the window instead of the price. Every Indonesian zone is AHEAD of UTC,
        // so it is the first hours of a local day that fall into the previous UTC day: 00:30 on
        // Monday in Jayapura is 15:30 on Sunday in UTC. These two showings are on different days
        // in Papua and the SAME day in UTC, so a date_trunc over the raw instants would add them
        // together and refuse the second hold.
        //
        // The branch carries the zone, not the hall: poolAtSite writes 'Asia/Jakarta' into
        // resource_pool.timezone exactly as V1's NOT NULL DEFAULT does for every untouched hall,
        // so reading the hall first would silently use Jakarta here (see CalendarPricing.zoneFor).
        val perHold = perHold()
        val papua = fixture.siteInZone(currentTenant.get(), "Demo Cinema Jayapura", "Jayapura", "Asia/Jayapura")
        val wit = ZoneId.of("Asia/Jayapura")
        val sundayEvening = at(MONDAY.minusDays(1), 20, 0, wit)
        val earlyMonday = at(MONDAY, 0, 30, wit)
        assertEquals(DayOfWeek.SUNDAY, sundayEvening.atZone(ZoneId.of("UTC")).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, earlyMonday.atZone(ZoneId.of("UTC")).dayOfWeek,
            "both instants must be a Sunday in UTC, or this test is not testing the trap")

        val hall = hall(papua, perHold)
        assertTrue(hold(hall, showingAt(hall, sundayEvening), perHold).success)

        val monday = hold(hall, showingAt(hall, earlyMonday), perHold)

        assertTrue(monday.success,
            "00:30 in Jayapura is a Monday there, however it reads in UTC: "
                + monday.failureReason)
    }

    // --- What a refusal must not cost --------------------------------------------------------

    @Test
    @TestSecurity(user = "day5@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "booking.email", value = "day5@demo-cinema.example")])
    fun aRefusedHoldTakesNoSeatOffSale() {
        // The failure that would make this cap worse than not having it. Unlike the per-showtime
        // check this one runs INSIDE the transaction, so "nothing to unwind" is a property of
        // where it sits in the method rather than something it gets for free — it refuses before
        // the reservation_group insert, and the whole transaction commits having written nothing
        // but the customer's own row.
        val perHold = perHold()
        val hall = hall(null, perHold)
        val morning = showingAt(hall, at(MONDAY.plusDays(2), 10, 0, WIB))
        val evening = showingAt(hall, at(MONDAY.plusDays(2), 20, 0, WIB))

        assertTrue(hold(hall, morning, perHold).success)
        assertFalse(hold(hall, evening, perDay() - perHold + 1).success)

        val map: List<SeatMapEntry> = catalogService.getSeatMap(hall.pool.resourcePoolId,
            evening.resourcePoolSessionId)
        assertEquals(perHold, map.size)
        assertTrue(map.stream().allMatch { s -> "available" == s.seatStatus },
            "every seat of the refused showing must still be on sale: "
                + map.stream().filter { s -> "available" != s.seatStatus }.toList())
    }

    // --- Which rows count ---------------------------------------------------------------------

    @Test
    // Staff, and not because staff are the interesting caller: this method has to cancel what it
    // booked, and assertMayActOn compares CurrentActor.customerId() — an attribute the augmentor
    // sets and @TestSecurity bypasses, so a customer-role caller is refused on its own booking.
    // Staff pass, and resolve their customer by phone, which is why the number
    // below is fixed rather than generated: it is this method's identity.
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun releasedSeatsStopCounting_becauseTheyWentBackOnSale() {
        val perHold = perHold()
        val hall = hall(null, perHold)
        val morning = showingAt(hall, at(MONDAY.plusDays(3), 10, 0, WIB))
        val evening = showingAt(hall, at(MONDAY.plusDays(3), 20, 0, WIB))

        val first = hold(hall, morning, perHold, 0, "+628117770001")
        assertTrue(first.success, first.failureReason)
        for (r in first.reservations) {
            assertTrue(bookingService.cancelReservation(r.reservationId, "tenant_staff",
                "test: released"))
        }

        val second = hold(hall, evening, perHold, 0, "+628117770001")

        assertTrue(second.success,
            "cancelled seats are somebody else's now, so they cannot still be this "
                + "customer's allowance: " + second.failureReason)
    }

    @Test
    // Staff for the same harness reason as above, plus one of its own: confirmGroup goes through
    // assertMayActOn too, and this method needs confirmed rows to have something to complete.
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun aCompletedShowingsSeatsStillCountForTheRestOfItsDay() {
        // 'completed' is the same ticket as 'confirmed' — the only difference is that the sweep
        // has since run over a showing that ended. Leaving it out of the count would make the
        // answer depend on sweep timing: the morning's seats would count against an evening
        // booking made at 14:00 and stop counting at 22:00, with nothing in the code saying so.
        //
        // Real dates, not 2099, because completePastShowings is bounded by now() and nothing in
        // a test can move the clock. Yesterday, so both showings are past whatever time of day
        // this runs at.
        val perHold = perHold()
        val perDay = perDay()
        val yesterday = LocalDate.now(WIB).minusDays(1)
        val hall = hall(null, perHold)
        val morning = showingAt(hall, at(yesterday, 10, 0, WIB))
        val evening = showingAt(hall, at(yesterday, 20, 0, WIB))

        val held = hold(hall, morning, perHold, 0, "+628117770002")
        assertTrue(held.success, held.failureReason)
        assertTrue(bookingService.confirmGroup(held.group!!.reservationGroupId))
        val tenantId = currentTenant.id()
        val completed: Int = db.inTenantTransaction(tenantId) { c ->
            reservationRepo.completePastShowings(c, tenantId) }
        assertTrue(completed >= perHold, "the sweep must have taken these rows to 'completed'")

        val refused = hold(hall, evening, perDay - perHold + 1, 0,
            "+628117770002")

        assertFalse(refused.success, "a finished screening's tickets were still bought")
        assertTrue(refused.failureReason!!.contains("already have " + perHold + " seats"),
            refused.failureReason)
    }

    // --- The channel that is exempt ------------------------------------------------------------

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun counterSalesAreNotCappedPerDayEither_andTheReasonIsStrongerHere() {
        // Every walk-in in the building attaches to the ONE placeholder customer row, so a
        // per-customer daily count applied at the counter would refuse the eleventh walk-in
        // ticket of the day because ten other people had already bought one. Two full holds on
        // one day is more than the daily cap by itself, and both must go through.
        val perHold = perHold()
        assertTrue(perHold * 2 > perDay(), "this scenario only bites if two holds exceed a day")
        val hall = hall(null, perHold)
        val morning = showingAt(hall, at(MONDAY.plusDays(4), 10, 0, WIB))
        val evening = showingAt(hall, at(MONDAY.plusDays(4), 20, 0, WIB))

        val first = bookingService.holdForCounterSale(
            hall.pool.resourcePoolId, morning.resourcePoolSessionId,
            hall.seatIds(perHold, 0))
        val second = bookingService.holdForCounterSale(
            hall.pool.resourcePoolId, evening.resourcePoolSessionId,
            hall.seatIds(perHold, 0))

        assertTrue(first.success, first.failureReason)
        assertTrue(second.success,
            "the counter must keep selling after the day's self-service allowance would be "
                + "gone: " + second.failureReason)
    }

    // --- What the customer is told before they start ticking ------------------------------------

    @Test
    fun theSeatMapStatesTheDailyLimitTooBeforeTheCustomerHasTickedAnything() {
        // The per-day cap is the surprising one — it can refuse a selection that looks fine on
        // the page in front of them — and it is the one the browser could not enforce even in
        // principle, because it does not know what this customer booked this morning. Saying it
        // up front is the whole of the warning they get.
        val hall = hall(null, 2)
        showingAt(hall, at(MONDAY.plusDays(5), 20, 0, WIB))
        val showing = showingAt(hall, at(MONDAY.plusDays(5), 23, 0, WIB))

        val body = given().`when`()
            .get("/halls/" + hall.pool.resourcePoolId + "/showings/"
                + showing.resourcePoolSessionId)
            .then().statusCode(200).extract().body().asString()

        assertTrue(body.contains("Up to " + purchaseCap.maxSeatsPerDay() + " seats a day"),
            "the seat map must state the daily cap, not only the per-booking one")
    }

    // --- scaffolding ----------------------------------------------------------------------------

    private data class Hall(val pool: ResourcePool, val seats: List<ResourceUnit>) {
        /** `count` seats starting at `from`, so two holds on one showing can ask for
         * different ones without meeting the EXCLUDE constraint instead of the cap. */
        fun seatIds(count: Int, from: Int): List<UUID?> {
            val ids = ArrayList<UUID?>()
            for (i in from until from + count) {
                ids.add(seats[i].resourceUnitId)
            }
            return ids
        }
    }

    private fun perHold(): Int {
        val perHold = purchaseCap.maxSeatsPerHold()
        assertTrue(perHold > 0, "these scenarios need both caps on")
        return perHold
    }

    private fun perDay(): Int {
        val perDay = purchaseCap.maxSeatsPerDay()
        assertTrue(perDay > purchaseCap.maxSeatsPerHold(),
            "this class assumes the daily cap is the looser of the two, so that a single "
                + "hold cannot reach it and the count is what refuses")
        return perDay
    }

    /** A flat-priced hall with `seatCount` seats and no showings yet — the showings are
     * added per test, because what varies here is when they start. */
    private fun hall(site: Site?, seatCount: Int): Hall {
        val tenant: Tenant = currentTenant.get()
        val pool: ResourcePool = if (site == null)
            fixture.pool(tenant)
        else
            fixture.poolAtSite(tenant, site, "Studio " + UUID.randomUUID())
        val seats = ArrayList<ResourceUnit>()
        for (i in 1..seatCount) {
            seats.add(fixture.unit(tenant, pool, "D" + i, "D", i))
        }
        fixture.rateRule(tenant, pool, BigDecimal("50000"))
        return Hall(pool, seats)
    }

    private fun showingAt(hall: Hall, startsAt: Instant): ResourcePoolSession =
        fixture.sessionAt(currentTenant.get(), hall.pool, startsAt, "on_sale")

    private fun hold(hall: Hall, showing: ResourcePoolSession, seatCount: Int): BookingService.HoldResult =
        hold(hall, showing, seatCount, 0)

    private fun hold(hall: Hall, showing: ResourcePoolSession, seatCount: Int,
                     fromSeat: Int): BookingService.HoldResult =
        hold(hall, showing, seatCount, fromSeat, "+628117779999")

    /**
     * @param phone contact detail on the customer-role path, where `booking.email` is the
     *        identity — but the identity itself on the staff-role path, which resolves the
     *        customer by phone because there is no login to key on.
     */
    private fun hold(hall: Hall, showing: ResourcePoolSession, seatCount: Int,
                     fromSeat: Int, phone: String): BookingService.HoldResult =
        bookingService.hold(hall.pool.resourcePoolId, showing.resourcePoolSessionId,
            hall.seatIds(seatCount, fromSeat), "Daily Cap Customer", phone,
            AgeClassification.Attestation.NOT_TICKED)
}
