package com.protek.booking.concurrency

import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CatalogService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.SeatMapPlan
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A booking and a relayout arriving at the same seat at the same moment.
 *
 * The window is the one the hold race already named and already has a seam for: `hold` reads
 * the showing, then inserts. A relayout landing inside that window is the case where a seat
 * could be retired out from under a booking, or a booking could land on a seat that has just
 * left the room — and either outcome would be a customer holding a ticket for a seat that does
 * not exist.
 *
 * What makes it safe is a pair of locks rather than a check: the relayout takes
 * `FOR UPDATE` on the hall's seats, and the hold takes `FOR SHARE` on the seats it
 * is about to book. This test drives the interleaving deterministically with the existing
 * `booking.test.race-delay-ms` seam instead of hoping two threads collide.
 *
 * The proof, as in [SessionCancellationRaceTest], is **ordering** rather than final
 * state: the relayout is shown to have *waited* for a booking that had not yet committed,
 * and to have then refused rather than retiring the seat that booking was taking. Without the
 * `FOR SHARE` half, the relayout would sail past an uncommitted hold, see nothing claiming
 * seat A4, retire it, and leave a customer holding a seat that is no longer in the room. The
 * other ordering — a relayout that got there first — needs no threads at all and is
 * `SeatMapRelayoutTest.holdingASeatThatWasJustRetiredIsRefused`.
 */
@QuarkusTest
@TestProfile(RaceDelayTestProfile::class)
class SeatRetirementRaceTest {

    companion object {
        private val PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun aRelayoutWaitsForABookingInFlightAndThenRefusesToRetireTheSeatItTook() {
        val tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")
        fixture.rateRule(tenant, hall, PRICE)
        val showing = fixture.session(tenant, hall, "on_sale")
        val a4 = catalogService.listSeats(hall.resourcePoolId)[3].resourceUnitId

        val executor = Executors.newSingleThreadExecutor()
        try {
            // Takes FOR SHARE on A4, then sleeps 2s inside the same transaction before its
            // insert — so for those two seconds this booking exists and nothing has committed.
            val booking: Future<BookingService.HoldResult> = executor.submit(Callable {
                // java.util.List.of, as the Java had it: it refuses a null id where listOf would not.
                bookingService.hold(hall.resourcePoolId,
                        showing.resourcePoolSessionId, java.util.List.of(a4), "Racing Customer",
                        "+628120000009", AgeClassification.Attestation.NOT_TICKED)
            })

            // Comfortably inside that window, and comfortably after it has started.
            TimeUnit.MILLISECONDS.sleep(700)
            val relayoutStartedAt = Instant.now()
            val refused = assertThrows(IllegalArgumentException::class.java) {
                    catalogService.applyRelayout(hall.resourcePoolId, SeatMapPlan.parse("A: 1-3")) }
            val relayoutFinishedAt = Instant.now()

            val result = booking.get(30, TimeUnit.SECONDS)
            assertTrue(result.success, "the booking that got there first must still be a booking")
            assertTrue(refused.message!!.contains("A4"), refused.message)

            // The ordering evidence is the refusal above: the relayout names A4 only if it saw
            // the hold, and it can only see the hold after the hold commits. The duration below
            // adds that it got there by blocking, not by arriving late.
            //
            // There used to be a third assertion, relayoutFinishedAt >= holdFinishedAt (the hold thread's clock after hold() returned), and it
            // was unsound. That clock was read after hold() returned, which includes its
            // post-commit push work, while the relayout is released at the commit itself. Under
            // a loaded full suite the relayout finished first (2026-10-09, booking-platform's
            // native verify: "the relayout must not have finished before the booking it was
            // blocked on"), and then passed 3/3 alone. It failed on correct behaviour.
            assertTrue(Duration.between(relayoutStartedAt, relayoutFinishedAt).toMillis() >= 500,
                    "the relayout should have spent the rest of the hold's window waiting on its lock")
        } finally {
            executor.shutdownNow()
        }

        assertEquals(listOf("A1", "A2", "A3", "A4"),
                catalogService.getSeatMap(hall.resourcePoolId, showing.resourcePoolSessionId).stream()
                        .map { it.label }.toList(),
                "A4 is still in the room, because somebody is in it")
    }
}
