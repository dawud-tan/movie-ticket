package com.protek.booking.concurrency

import com.protek.booking.db.Db
import com.protek.booking.domain.Customer
import com.protek.booking.domain.PolicyDocument
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.service.PurchaseCap
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
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
import java.util.stream.Collectors

/**
 * The differential half of [DailyPurchaseCapConcurrencyTest]: the same four holds by one
 * customer on four showings of one day, with exactly one thing taken away — the
 * `SELECT … FOR UPDATE` on the customer row that `BookingService.refuseIfDayIsFull`
 * takes before it counts. The count and the refusal both stay. If the cap still held here, the
 * other test passing would say nothing about the lock; it does not hold, and that is what makes
 * the other test evidence.
 *
 * **This is Adya's G2, measured.** Each hold counts the customer's seats for the day — a
 * read over rows its rivals are about to insert — and then inserts. Under READ COMMITTED, which
 * is what this application runs at (it sets no level of its own), no transaction sees another's
 * uncommitted rows, so all four count the same total and all four pass. Every pair of them has an
 * anti-dependency each way, which is a cycle, and twelve seats land against a cap of ten. The
 * isolation level does not stop that. The lock does, by making the four take turns on the one row
 * that exists before any of them has written anything.
 *
 * The same experiment was done by hand when the daily cap shipped: deleting the
 * `findByIdForUpdate` call gave four winners, twelve seats and zero refusals, three runs
 * out of three. This class is that manual mutation, kept.
 *
 * **Composed from repository calls, like [SessionCancellationRaceDifferentialTest]**,
 * because the weakened path belongs in a test and never in production code. It follows
 * `holdInternal`'s own order — the showing lock, the count, the insert — so the only
 * difference from production is the missing line, marked where it would be.
 *
 * **Why a delay, when the positive test has none.** With the lock, the lock decides the
 * outcome whatever the timing. Without it, the outcome depends on every count landing before any
 * rival commits, and four threads released together only usually manage that. The remedy
 * is the usual one: a sleep between the check and the act, which widens the window until the race is lost
 * every time rather than most times.
 *
 * **And a sequential control**, because "the cap was exceeded" would prove nothing if the
 * composed check were simply wrong. The same attempts, run one after another, stop at the cap, so
 * what lets the fourth through in the race is the concurrency and nothing else.
 */
@QuarkusTest
class DailyPurchaseCapConcurrencyDifferentialTest {

    companion object {
        private val WIB = ZoneId.of("Asia/Jakarta")
        /** Thursday 19 November 2099 — a week after [DailyPurchaseCapConcurrencyTest]'s day, and
         * one no other test class puts fixtures on. */
        private val RACE_DAY = LocalDate.of(2099, 11, 19)
        private const val ATTEMPTS = 4
        private const val SEATS_PER_ATTEMPT = 3
        private const val RACE_DELAY_MS = 1000
        private val PRICE = BigDecimal("50000")

        /** The day as the cap counts it: a local date in the branch's zone, which for a fixture hall
         * is Asia/Jakarta (see refuseIfDayIsFull on why it is never a UTC day). */
        private fun dayStart(): Instant = RACE_DAY.atStartOfDay(WIB).toInstant()

        private fun dayEnd(): Instant = RACE_DAY.plusDays(1).atStartOfDay(WIB).toInstant()

        private fun counts(attempts: List<Attempt>): String =
            attempts.stream().map { a -> a.counted.toString() }.collect(Collectors.joining(", ", "[", "]"))

        private fun sleepForTest(millis: Int) {
            try {
                Thread.sleep(millis.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var purchaseCap: PurchaseCap
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var fixture: TestFixture

    /** What one hold saw and did: the day's total it counted, and whether it then took seats. */
    private data class Attempt(val counted: Int, val held: Boolean)

    /** One customer, three seats, and four showings of [RACE_DAY] that do not overlap in
     * time — so the EXCLUDE constraint has nothing to say and the cap is the only thing that can
     * refuse anyone. A fresh tenant per scenario, so the count can only ever see this test's rows. */
    private data class Scenario(val tenantId: UUID, val pool: ResourcePool, val seats: List<ResourceUnit>,
                                val customer: Customer, val policy: PolicyDocument,
                                val showings: List<ResourcePoolSession>, val perDay: Int)

    @Test
    fun withoutTheCustomerLock_fourSimultaneousHoldsAllPassTheCount_andTheDayEndsOverTheCap() {
        val s = scenario()
        val barrier = CyclicBarrier(ATTEMPTS)
        val executor = Executors.newFixedThreadPool(ATTEMPTS)
        try {
            val futures = ArrayList<Future<Attempt>>()
            for (showing in s.showings) {
                futures.add(executor.submit(Callable {
                    barrier.await(30, TimeUnit.SECONDS)
                    attempt(s, showing, RACE_DELAY_MS)
                }))
            }
            val attempts = ArrayList<Attempt>()
            for (f in futures) {
                attempts.add(f.get(30, TimeUnit.SECONDS))
            }
            val held = attempts.stream().filter { it.held }.count()
            val seatsForTheDay = seatsForTheDay(s)

            // The counts are in the message because they are the diagnosis: all zeros is the
            // anomaly, and anything else means a count landed after a rival committed — the race
            // window was missed and this run measured nothing.
            // A Long count, so the expected value is a Long: Java widened it to assertEquals(long, long).
            assertEquals(ATTEMPTS.toLong(), held, "without the customer lock every hold should pass the count;"
                    + " the totals they counted were " + counts(attempts))
            assertEquals(ATTEMPTS * SEATS_PER_ATTEMPT, seatsForTheDay)
            assertTrue(seatsForTheDay > s.perDay, "the anomaly: one customer holds " + seatsForTheDay
                    + " seats for one day against a cap of " + s.perDay
                    + " — exactly what CustomerRepository.findByIdForUpdate exists to make impossible")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun withoutTheCustomerLock_theSameHoldsOneAfterAnother_stillStopAtTheCap() {
        val s = scenario()
        val attempts = ArrayList<Attempt>()
        for (showing in s.showings) {
            attempts.add(attempt(s, showing, 0))
        }

        assertEquals((s.perDay / SEATS_PER_ATTEMPT).toLong(), attempts.stream().filter { it.held }.count(),
                "one at a time, the composed check must refuse exactly where production does, or the race"
                        + " above is measuring a broken check rather than a missing lock; counted "
                        + counts(attempts))
        assertTrue(seatsForTheDay(s) <= s.perDay)
    }

    /** One hold as `BookingService.holdInternal` makes it for the daily cap, minus the
     * customer lock. `raceDelayMs` is the race-window seam, between the count and the insert. */
    private fun attempt(s: Scenario, showing: ResourcePoolSession, raceDelayMs: Int): Attempt {
        val tenantId = s.tenantId
        return db.inTenantTransaction(tenantId) { c ->
            sessionRepo.findByIdForUpdate(c, tenantId, showing.resourcePoolSessionId).orElseThrow()
            // THE MISSING LINE. refuseIfDayIsFull takes
            //     customerRepo.findByIdForUpdate(c, tenantId, customer.customerId());
            // here, and it is the only thing that serialises two holds on different showings: each
            // showing lock above is a different row.
            val counted = reservationRepo.countSeatsForCustomerBetween(c, tenantId,
                    s.customer.customerId, dayStart(), dayEnd())
            if (counted + SEATS_PER_ATTEMPT > s.perDay) {
                return@inTenantTransaction Attempt(counted, false)
            }
            sleepForTest(raceDelayMs)
            val group = groupRepo.insert(c, tenantId, s.pool.resourcePoolId,
                    s.customer.customerId, SEATS_PER_ATTEMPT)
            for (seat in s.seats) {
                reservationRepo.insertHold(c, tenantId, group.reservationGroupId, seat.resourceUnitId,
                        showing.resourcePoolSessionId, s.policy.policyDocumentId, showing.startsAt,
                        showing.endsAt, PRICE, "IDR", Instant.now().plus(Duration.ofMinutes(10)), "customer",
                        UUID.randomUUID())
            }
            Attempt(counted, true)
        }
    }

    private fun scenario(): Scenario {
        val perDay = purchaseCap.maxSeatsPerDay()
        assertTrue(perDay > 0 && perDay < ATTEMPTS * SEATS_PER_ATTEMPT,
                "this only means anything if the four holds together exceed the cap")
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val seats = ArrayList<ResourceUnit>()
        for (i in 1..SEATS_PER_ATTEMPT) {
            seats.add(fixture.unit(tenant, pool, "R" + i, "R", i))
        }
        val showings = ArrayList<ResourcePoolSession>()
        for (i in 0 until ATTEMPTS) {
            // 10:00, 13:00, 16:00 and 19:00, two hours each: one day, no overlap.
            showings.add(fixture.sessionAt(tenant, pool,
                    ZonedDateTime.of(RACE_DAY, LocalTime.of(10 + i * 3, 0), WIB).toInstant(), "on_sale"))
        }
        return Scenario(tenant.tenantId, pool, seats, fixture.customer(tenant), fixture.policy(tenant),
                showings, perDay)
    }

    private fun seatsForTheDay(s: Scenario): Int =
        db.inTenantTransaction(s.tenantId) { c -> reservationRepo.countSeatsForCustomerBetween(
                c, s.tenantId, s.customer.customerId, dayStart(), dayEnd()) }
}
