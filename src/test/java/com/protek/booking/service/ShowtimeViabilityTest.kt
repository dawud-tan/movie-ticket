package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The viability rule, both halves.
 *
 * Recorded at the Rekaan branch on Saturday 22 August 2026: a screening only runs if at
 * least two seats are sold, and one that has not sold two by fifteen minutes past its advertised
 * start is cancelled. The two halves are one rule and are tested together, because either alone
 * is incoherent — a minimum with no consequence is advice, and a cancellation with no minimum
 * would strand the customer who bought the single ticket.
 *
 * Runs under [ViabilityRuleTestProfile], which turns the rule on; see that class for
 * why it is off elsewhere.
 *
 * **In `com.protek.booking.service` rather than beside the other lifecycle tests**,
 * because `ReservationLifecycleService.sweep()` was package-private — it is invoked by the
 * scheduler and by nothing else in production, and widening it to `public` for a test
 * would advertise an entry point that should not have callers. Kotlin has no package-private,
 * so it is public now; the test stayed where it was.
 */
@QuarkusTest
@TestProfile(ViabilityRuleTestProfile::class)
class ShowtimeViabilityTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var lifecycle: ReservationLifecycleService
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var db: Db

    private data class Screening(val tenant: Tenant, val hall: ResourcePool, val showing: ResourcePoolSession,
                                 val seats: List<ResourceUnit>)

    private fun screening(startsAt: Instant): Screening {
        val tenant = currentTenant.get()
        val site: Site = fixture.site(tenant, "Demo Cinema Rekaan", "Rekaan-" + UUID.randomUUID())
        val hall = fixture.poolAtSite(tenant, site, "Studio 1")
        val film: Film = fixture.film(tenant, "Viability " + UUID.randomUUID(), "SU")
        fixture.rateRule(tenant, hall, BigDecimal("35000"))
        val seats = listOf(
            fixture.unit(tenant, hall, "A1", "A", 1),
            fixture.unit(tenant, hall, "A2", "A", 2),
            fixture.unit(tenant, hall, "A3", "A", 3))
        return Screening(tenant, hall,
            fixture.sessionOfFilmAt(tenant, hall, film, startsAt, "on_sale"), seats)
    }

    private fun upcoming(): Screening = screening(Instant.now().plus(Duration.ofDays(1)))

    private fun hold(screening: Screening, vararg seats: ResourceUnit): BookingService.HoldResult =
        bookingService.hold(screening.hall.resourcePoolId,
            screening.showing.resourcePoolSessionId,
            seats.map { it.resourceUnitId },
            "Test Customer", "+62812000000", null)

    private fun statusOf(screening: Screening): String? =
        db.inTenantTransaction(screening.tenant.tenantId) { c ->
            sessionRepo.findById(c, screening.tenant.tenantId,
                screening.showing.resourcePoolSessionId).orElseThrow() }.status

    @Test
    fun theFirstBuyerCannotTakeASingleSeat() {
        val screening = upcoming()

        val result = hold(screening, screening.seats[0])

        assertFalse(result.success)
        assertTrue(result.failureReason!!.contains("at least 2 seats"), result.failureReason)
        // The refusal explains the rule's other half, because that is what makes the first half
        // make sense. A customer told only "buy two" reads it as a sales tactic.
        assertTrue(result.failureReason.contains("cancels a screening"), result.failureReason)
    }

    @Test
    fun theFirstBuyerTakingTwoSeatsSucceeds() {
        val screening = upcoming()

        val result = hold(screening, screening.seats[0], screening.seats[1])

        assertTrue(result.success, result.failureReason)
        assertEquals(2, result.reservations.size)
    }

    @Test
    fun onceTheShowingIsViableASingleSeatIsAnOrdinarySale() {
        val screening = upcoming()
        assertTrue(hold(screening, screening.seats[0], screening.seats[1]).success)

        val third = hold(screening, screening.seats[2])

        // Only the FIRST buyer is bound, which is the rule as the branch states it: once two
        // seats are sold the screening runs, so the third ticket has nothing left to prove and
        // refusing it would cost the branch money for no reason.
        assertTrue(third.success, third.failureReason)
    }

    @Test
    fun theRuleAppliesToTheCounterToo() {
        val screening = upcoming()

        val result = bookingService.holdForCounterSale(
            screening.hall.resourcePoolId, screening.showing.resourcePoolSessionId,
            listOf(screening.seats[0].resourceUnitId))

        // Unlike the purchase caps, which the counter is exempt from. Those are an
        // anti-scalping control on the self-service channel; this is the branch's own operating
        // rule, and staff selling one seat at the desk creates the exact problem it prevents.
        assertFalse(result.success)
        assertTrue(result.failureReason!!.contains("at least 2 seats"), result.failureReason)
    }

    @Test
    fun aScreeningThatSoldNothingIsCancelledAfterItsGracePeriod() {
        val screening = screening(Instant.now().minus(Duration.ofMinutes(20)))
        assertEquals("on_sale", statusOf(screening))

        lifecycle.sweep()

        assertEquals("cancelled", statusOf(screening))
    }

    @Test
    fun aScreeningThatSoldTwoSeatsIsLeftAlone() {
        val screening = screening(Instant.now().minus(Duration.ofMinutes(20)))
        assertTrue(hold(screening, screening.seats[0], screening.seats[1]).success)

        lifecycle.sweep()

        assertEquals("on_sale", statusOf(screening))
    }

    @Test
    fun aScreeningStillInsideItsGracePeriodIsLeftAlone() {
        val screening = screening(Instant.now().minus(Duration.ofMinutes(5)))

        lifecycle.sweep()

        // The rule gives a screening fifteen minutes past its advertised start before deciding.
        // Without this scenario the grace is untested in one direction: a sweep that subtracted
        // the interval on the wrong side of now() would cancel screenings BEFORE they start, and
        // every other assertion here would still pass.
        assertEquals("on_sale", statusOf(screening))
    }

    @Test
    fun aScreeningThatHasNotStartedYetIsNeverCancelledHoweverEmptyItIs() {
        val screening = upcoming()

        lifecycle.sweep()

        // The clock runs from starts_at, not from when the schedule was published. Measured from
        // publication, this sweep would cancel next month's listings the moment they went up.
        assertEquals("on_sale", statusOf(screening))
    }

    @Test
    fun aCancelledScreeningRefusesFurtherHolds() {
        val screening = screening(Instant.now().minus(Duration.ofMinutes(20)))
        lifecycle.sweep()

        val result = hold(screening, screening.seats[0], screening.seats[1])

        // The sweep sets the session status, and holdInternal's existing status check is what
        // enforces it — the rule needed no second gate, which is why none was added.
        assertFalse(result.success)
        assertTrue(result.failureReason!!.contains("not currently open for booking"),
            result.failureReason)
    }

    @Test
    fun sweepingTwiceCancelsNothingTwice() {
        val screening = screening(Instant.now().minus(Duration.ofMinutes(20)))

        lifecycle.sweep()
        lifecycle.sweep()

        // The query only looks at 'scheduled' and 'on_sale', so a cancelled showing drops out of
        // it. Worth pinning: the sweep runs every 30 seconds forever, and a rule that kept
        // re-cancelling would write an audit row and fire a push on every pass.
        assertEquals("cancelled", statusOf(screening))
    }
}
