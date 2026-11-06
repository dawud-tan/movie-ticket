package com.protek.booking.concurrency

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.service.BookingService
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

/**
 * The concurrency acceptance test: N concurrent booking attempts for the identical
 * seat and an overlapping period, released together via a [CyclicBarrier] so they
 * fire as close to simultaneously as real concurrent HTTP requests would. Expect exactly
 * one success; the rest must fail specifically with `23P01` (exclusion_violation) —
 * not merely "some failure," which could also mean the test is broken.
 *
 * An attempt the database kills to break a deadlock (`40P01`) is re-run before its
 * answer is taken, because that is not an answer — same bounded loop, and the same reasoning, as
 * `BookingService.holdTransaction` in production. The retries are counted and reported in
 * the assertion messages, so this cannot quietly become a test of how patient the loop is.
 *
 * **This class used to flake under full-suite load about one run in six, for nine
 * sightings between 2026-08 and 2026-09-17. It was fixed on 2026-09-17, and the answer was not
 * environmental — it was this test racing a code path that production does not have.**
 *
 * Every hold in production enters `BookingService.holdInternal`, whose *first*
 * statement is `sessionRepo.findByIdForUpdate` on the showing row (the re-validation
 * lock). Eight customers pressing Hold on one showing therefore queue on that one row and reach
 * the exclusion check one at a time. This test called `reservationRepo.insertHold`
 * directly, so all eight arrived at the check together — and PostgreSQL said exactly what that
 * costs:
 *
 * ```
 * ERROR: deadlock detected
 *   Detail: Process 175 waits for ShareLock on transaction 3948; blocked by process 168.
 *           Process 168 waits for ShareLock on transaction 3946; blocked by process 175.
 *   Where: while checking exclusion constraint on tuple (17,6) in relation "reservation"
 * ```
 *
 * Both parties are the racing inserters themselves. Two speculative inserts against one
 * exclusion key, each waiting on the other's transaction to find out whether its own conflict
 * stands — a symmetric wait with no third party in it, no sweep, no load, nothing to tune. That
 * is why all four fixes tried on 2026-09-15 failed: raising the retry bound 3 to 8, cutting
 * `deadlock_timeout` to 100ms, 20-120ms of random backoff, and a separate JVM all change
 * how the collision is handled and none of them stop eight threads colliding.
 *
 * **It was never rare, either, and that is the measurement that unlocked it.** "Never
 * fails in isolation" was ten runs of ONE race in ten cold JVMs. Run the same race 25 times in
 * one warm JVM and it fails 14 times — the first race straggles because nothing is JIT-compiled
 * yet, and a warm barrier release puts all eight threads on the constraint inside the same few
 * microseconds. A full suite is simply the thing that warms the JVM up; the "load" was never the
 * point. Three controls, 25 rounds each, all against the same warm JVM:
 *
 * ```
 *   sweep every 1s + 120 stale holds : 17/25 failed   <- the hypothesis being tested
 *   sweep off      + 120 stale holds : 15/25 failed   <- so the sweep is not it
 *   sweep off      +   0 stale holds : 14/25 failed   <- nor is table volume
 *   sweep off + 0 stale + THIS LOCK  :  0/25 failed
 * ```
 *
 * **Taking the lock does not weaken what this test proves, and that is not an argument — it
 * is measured next door.** [SeatBookingConcurrencyDifferentialTest] runs this same race
 * against a scratch schema with the constraint dropped and asserts that all eight inserts
 * succeed. Eight successes is what `assertEquals(1, successes)` below refuses, so the
 * assertions here still fail the moment the constraint stops doing the work.
 * The constraint is still the sole arbiter and still the thing producing the seven 23P01s —
 * The rule is about never putting an availability *read* in front of the constraint,
 * and a `FOR UPDATE` on the showing row is not one. What changed is that the test now
 * races the path a customer's request actually takes.
 *
 * The differential counterpart, [SeatBookingConcurrencyDifferentialTest], runs the
 * identical race against a scratch schema with the `EXCLUDE` constraint dropped and
 * confirms double-booking actually happens there — evidence that this test is measuring
 * the constraint, not passing by accident.
 */
@QuarkusTest
class SeatBookingConcurrencyTest {

    companion object {
        private const val CONCURRENT_ATTEMPTS = 8
        /** **Is** `BookingService.DEADLOCK_ATTEMPTS`, not a copy of it — this loop is that
         * loop, at the level this test races, and one number cannot drift from itself. Raising it was
         * measured and did not help; see the class javadoc. */
        private const val DEADLOCK_ATTEMPTS = BookingService.DEADLOCK_ATTEMPTS
        private val PRICE = BigDecimal("50000.00")
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var fixture: TestFixture

    /**
     * @param attempts how many tries this thread needed. Carried so the bound can be judged on
     * evidence rather than on nobody having complained: a run where the worst thread used 2 of 8
     * says the bound has headroom, and one where it used all of them says the next sighting is coming.
     */
    private data class AttemptResult(val success: Boolean, val sqlState: String?, val attempts: Int)

    @Test
    fun concurrentHoldsForIdenticalSeatAndOverlappingPeriod_exactlyOneSucceeds() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)

        // One reservation_group per attempt, created up front so the race below is purely
        // on the reservation insert against the shared seat/period, not incidental
        // contention on unrelated setup rows.
        val groups = ArrayList<ReservationGroup>()
        for (i in 0 until CONCURRENT_ATTEMPTS) {
            groups.add(fixture.group(tenant, pool, customer, 1))
        }

        val deadlocks = AtomicInteger()
        val barrier = CyclicBarrier(CONCURRENT_ATTEMPTS)
        val executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)
        try {
            val futures = ArrayList<Future<AttemptResult>>()
            for (i in 0 until CONCURRENT_ATTEMPTS) {
                val group = groups[i]
                futures.add(executor.submit(Callable<AttemptResult> {
                    barrier.await(30, TimeUnit.SECONDS)
                    // Re-run on 40P01, exactly as BookingService.holdTransaction does in
                    // production, and for the reason spelled out there: a deadlock is not an
                    // answer.
                    //
                    // KEPT, THOUGH IT SHOULD NEVER FIRE NOW. The session lock above is taken
                    // first by every thread, which is single-lock ordering and cannot cycle, and
                    // 25 warm rounds produced zero deadlocks. So the counter below is no longer
                    // a measure of patience — it is the regression detector: if someone removes
                    // that lock to "simplify" this test, the count goes non-zero and the
                    // assertion at the end says so in one line instead of returning this class
                    // to a flake nobody can place.
                    var attempt = 1
                    while (true) {
                        try {
                            db.inTenantTransaction(tenant.tenantId) { c ->
                                // THE LOCK PRODUCTION TAKES, and the reason this test stopped
                                // flaking. holdInternal opens every hold with exactly this
                                // statement, so eight customers pressing Hold on one showing
                                // queue on one row and reach the exclusion check one at a time.
                                // Racing insertHold without it put all eight into the check
                                // together, which is a symmetric deadlock and not a thing any
                                // request can do — see the class javadoc for PostgreSQL's own
                                // account of it.
                                //
                                // It is a lock, not a pre-check: it reads no availability and
                                // decides nothing about whether a seat is free. The EXCLUDE
                                // constraint below is still the only thing that answers that,
                                // and it is still what produces the seven 23P01s asserted on.
                                sessionRepo.findByIdForUpdate(c, tenant.tenantId,
                                    session.resourcePoolSessionId)
                                reservationRepo.insertHold(
                                    c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId,
                                    session.resourcePoolSessionId, policy.policyDocumentId,
                                    session.startsAt, session.endsAt, PRICE, "IDR",
                                    Instant.now().plusSeconds(600), "customer", UUID.randomUUID())
                            }
                            return@Callable AttemptResult(true, null, attempt)
                        } catch (e: DataAccessException) {
                            if (attempt >= DEADLOCK_ATTEMPTS || !e.isDeadlock()) {
                                return@Callable AttemptResult(false, e.sqlState(), attempt)
                            }
                            // Counted rather than swallowed: the retry must not be able to hide
                            // that contention is happening, or the next person tuning this loses
                            // the evidence the way four full-suite runs already did.
                            deadlocks.incrementAndGet()
                        }
                        attempt++
                    }
                    // Never reached: the loop leaves only by returning. Kotlin types a lambda by its
                    // last expression, and a while loop is not one.
                    throw IllegalStateException()
                }))
            }

            val results = ArrayList<AttemptResult>()
            for (f in futures) {
                results.add(f.get(30, TimeUnit.SECONDS))
            }

            val successes = results.stream().filter { it.success }.count()
            val conflictFailures = results.stream()
                .filter { r -> !r.success && "23P01" == r.sqlState }
                .count()
            val otherFailures = results.stream()
                .filter { r -> !r.success && "23P01" != r.sqlState }
                .count()

            // Name the states, do not just count them. This test has now flaked three times under
            // full-suite load with the identical shape — 7 of 8 "other" — and each time the
            // evidence went with the run, because the assertion reported a number and the
            // SQLSTATEs were already in hand. Read them before anybody
            // theorises again: a null state is a pool acquisition timeout, 40001/40P01 is a
            // serialisation or deadlock, and 23P01 is the mechanism working.
            val offending = results.stream()
                .filter { r -> !r.success && "23P01" != r.sqlState }
                .map { r -> r.sqlState.toString() }
                .collect(Collectors.joining(", "))
            val worstAttempts = results.stream().mapToInt { it.attempts }.max().orElse(0)
            val contention = " (" + deadlocks.get() + " deadlock retries along the way; worst" +
                " thread needed " + worstAttempts + " of " + DEADLOCK_ATTEMPTS + " attempts)"
            // Printed on success too, not only in a failure message. The bound was raised from 3
            // to 8 on evidence that arrived only as failures; the headroom it now
            // has is invisible unless a passing run says what it used.
            println("[SeatBookingConcurrencyTest] " + successes + " success, " +
                conflictFailures + " x 23P01, " + otherFailures + " other" + contention)
            assertEquals(0, deadlocks.get(), "the showing row is locked FOR UPDATE before the "
                + "insert, exactly as holdInternal does, so these eight attempts reach the "
                + "exclusion check one at a time and cannot deadlock. A non-zero count here "
                + "means that lock is gone — see the class javadoc before tuning anything"
                + contention)
            // Long counts, so the expected values are Longs: Java widened them to assertEquals(long, long).
            assertEquals(0L, otherFailures, "every non-winning attempt must fail specifically with "
                + "23P01, not some other error — got [" + offending + "]" + contention)
            assertEquals(1L, successes,
                "exactly one concurrent hold for the same seat/period must succeed" + contention)
            assertEquals((CONCURRENT_ATTEMPTS - 1).toLong(), conflictFailures)
        } finally {
            executor.shutdownNow()
        }
    }
}
