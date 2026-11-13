package com.protek.booking.concurrency

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.PurchaseCap
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The per-day purchase cap under concurrency: four of one customer's holds released
 * together, against a ceiling only three of them fit under.
 *
 * **Why this cap needs a race test when the per-showtime one does not.** That one compares
 * a list length to a number and is decided entirely inside one request. This one is decided by
 * *counting rows*, and under READ COMMITTED two concurrent transactions each see the
 * other's holds as absent — so both pass at 9 of 10 and eleven seats land. The realistic case is
 * not exotic: it is one person with the same showing open in two tabs, which is exactly the
 * population an anti-scalping cap exists for.
 *
 * **Four different showings, not four holds on one.** This is the detail that makes the
 * test measure what it claims to. Every hold takes `SELECT … FOR UPDATE` on its showing
 * first, so four attempts at a *single* showing are already serialised by that lock and
 * would pass with no per-customer lock at all — a green test proving nothing. Spread across four
 * showings on the same day, the session locks do not overlap, and the only thing that can
 * serialise them is `CustomerRepository.findByIdForUpdate`.
 *
 * **No security context, deliberately.** The attempts run on a plain executor, where there
 * is no request context and therefore no identity: `CurrentTenant.id()` falls back to the
 * configured tenant and `BookingService` resolves the customer by phone, which is why the
 * number below is fixed and the row is created up front. That keeps the measurement on the lock
 * rather than on whether a CDI request context propagates to a thread pool — the same reasoning
 * [AdmissionRescanConcurrencyTest] records.
 */
@QuarkusTest
class DailyPurchaseCapConcurrencyTest {

    companion object {
        private val WIB = ZoneId.of("Asia/Jakarta")
        /** Thursday 12 November 2099 — a day no other test class puts fixtures on. */
        private val RACE_DAY = LocalDate.of(2099, 11, 12)
        private const val PHONE = "+628117778888"
        private const val ATTEMPTS = 4
        private const val SEATS_PER_ATTEMPT = 3
    }

    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var purchaseCap: PurchaseCap
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var db: Db

    @Test
    fun concurrentHoldsByOneCustomerCannotAddUpToMoreThanTheDailyCap() {
        val perDay = purchaseCap.maxSeatsPerDay()
        assertTrue(perDay > 0 && perDay < ATTEMPTS * SEATS_PER_ATTEMPT,
                "this race only means anything if the four attempts together exceed the cap")
        val expectedWinners = perDay / SEATS_PER_ATTEMPT

        val tenant = currentTenant.get()
        val tenantId = tenant.tenantId
        // Created up front rather than left to the first hold: BookingService resolves an
        // identity-less caller by phone through queryOne, so four simultaneous misses would
        // insert four customer rows and the next lookup would die on "more than one row" —
        // a failure that names the query and not the cause.
        val customer = db.inTenantTransaction(tenantId) { c -> customerRepo.insert(
                c, tenantId, "Race Customer", PHONE, null, false) }
        assertNotNull(customer.customerId)

        val pool = fixture.pool(tenant)
        // Three seats, reused by every attempt. The showings do not overlap in time, so the
        // EXCLUDE constraint has nothing to say here and the cap is the only thing that can
        // refuse anyone — which is what this test needs to be measuring.
        val seats = ArrayList<UUID?>()
        for (i in 1..SEATS_PER_ATTEMPT) {
            seats.add(fixture.unit(tenant, pool, "R" + i, "R", i).resourceUnitId)
        }
        fixture.rateRule(tenant, pool, BigDecimal("50000"))
        val showings = ArrayList<ResourcePoolSession>()
        for (i in 0 until ATTEMPTS) {
            showings.add(fixture.sessionAt(tenant, pool,
                    ZonedDateTime.of(RACE_DAY, LocalTime.of(10 + i * 3, 0), WIB).toInstant(),
                    "on_sale"))
        }

        val barrier = CyclicBarrier(ATTEMPTS)
        val executor = Executors.newFixedThreadPool(ATTEMPTS)
        try {
            val futures = ArrayList<Future<BookingService.HoldResult>>()
            for (showing in showings) {
                futures.add(executor.submit(Callable {
                    barrier.await(30, TimeUnit.SECONDS)
                    bookingService.hold(pool.resourcePoolId,
                            showing.resourcePoolSessionId, seats, "Race Customer", PHONE,
                            AgeClassification.Attestation.NOT_TICKED)
                }))
            }

            var winners = 0
            var seatsHeld = 0
            val refusals = ArrayList<String?>()
            for (f in futures) {
                val result = f.get(30, TimeUnit.SECONDS)
                if (result.success) {
                    winners++
                    seatsHeld += result.reservations.size
                } else {
                    refusals.add(result.failureReason)
                }
            }

            assertEquals(expectedWinners, winners,
                    "the cap must hold across simultaneous holds, not only sequential ones; "
                            + "refusals were " + refusals)
            assertTrue(seatsHeld <= perDay,
                    "one customer ended the day with " + seatsHeld + " seats against a cap of "
                            + perDay)
            // Refused for the stated reason rather than by some incidental failure, which is
            // what would make a green count meaningless.
            assertTrue(refusals.stream().allMatch { r -> r!!.contains("a day") },
                    "every loser must be refused by the daily cap: " + refusals)
        } finally {
            executor.shutdownNow()
        }
    }
}
