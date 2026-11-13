package com.protek.booking.concurrency

import com.protek.booking.db.Db
import com.protek.booking.domain.Reservation
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The differential half of [SessionCancellationRaceTest]: the identical race, but the
 * session-status read uses [ResourcePoolSessionRepository.findById] (a plain
 * `SELECT`) instead of [ResourcePoolSessionRepository.findByIdForUpdate]
 * (`SELECT ... FOR UPDATE`). A plain read never blocks a concurrent writer, so the
 * cancellation is free to land — and complete — in the window between the booking's status
 * read and its insert. If `SessionCancellationRaceTest` passing were luck rather than
 * the fix actually doing something, this test would also pass; it doesn't, which is what
 * makes the other one evidence.
 *
 * This composes [ReservationRepository]/[ResourcePoolSessionRepository]
 * calls directly (bypassing `BookingService`/`CatalogService`, which always use
 * the FOR-UPDATE path) — the same "weakened path lives in the test, not in production code"
 * principle used by [SeatBookingConcurrencyDifferentialTest].
 */
@QuarkusTest
class SessionCancellationRaceDifferentialTest {

    companion object {
        private const val RACE_DELAY_MS = 2000
    }

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun withoutForUpdate_cancellationSlipsInBetweenReadAndInsert_orphansAReservation() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val customer = fixture.customer(tenant)
        val policy = fixture.policy(tenant)
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val holdReservation = AtomicReference<Reservation>()
        val holdCompletedAt = AtomicReference<Instant>()
        val cancelCompletedAt = AtomicReference<Instant>()
        val holdError = AtomicReference<Exception>()
        val cancelError = AtomicReference<Exception>()

        val bookingThread = Thread({
            try {
                val r = db.inTenantTransaction(tenant.tenantId) { c ->
                    val s = sessionRepo.findById(c, tenant.tenantId, session.resourcePoolSessionId)
                            .orElseThrow()
                    if ("on_sale" != s.status) {
                        throw IllegalStateException("not on sale: " + s.status)
                    }
                    sleepForTest(RACE_DELAY_MS)
                    val group = groupRepo.insert(c, tenant.tenantId, pool.resourcePoolId,
                            customer.customerId, 1)
                    reservationRepo.insertHold(c, tenant.tenantId, group.reservationGroupId,
                            unit.resourceUnitId, session.resourcePoolSessionId, policy.policyDocumentId,
                            session.startsAt, session.endsAt, BigDecimal("50000"), "IDR",
                            Instant.now().plus(Duration.ofMinutes(10)), "customer", UUID.randomUUID())
                }
                holdReservation.set(r)
                holdCompletedAt.set(Instant.now())
            } catch (e: Exception) {
                holdError.set(e)
            }
        }, "booking-thread")

        val cancelThread = Thread({
            try {
                // Same head start as SessionCancellationRaceTest: by the time this fires,
                // the booking thread must already be past its plain-SELECT read and into
                // the injected sleep, so this genuinely lands in that window rather than
                // racing to get there first.
                Thread.sleep(400)
                db.inTenantTransaction(tenant.tenantId) { c ->
                    sessionRepo.updateStatus(c, tenant.tenantId, session.resourcePoolSessionId, "cancelled") }
                cancelCompletedAt.set(Instant.now())
            } catch (e: Exception) {
                cancelError.set(e)
            }
        }, "cancel-thread")

        bookingThread.start()
        cancelThread.start()
        bookingThread.join(20_000)
        cancelThread.join(20_000)

        assertNull(holdError.get(), "booking thread threw: " + holdError.get())
        assertNull(cancelError.get(), "cancel thread threw: " + cancelError.get())
        assertNotNull(holdCompletedAt.get())
        assertNotNull(cancelCompletedAt.get())

        // The anomaly: without FOR UPDATE, the plain SELECT never blocked the cancellation,
        // so it slipped in and completed before the booking (which had already read
        // "on_sale" and committed to proceeding) finished its insert and commit.
        assertTrue(cancelCompletedAt.get().isBefore(holdCompletedAt.get()),
                "expected the cancellation (completed at " + cancelCompletedAt.get()
                        + ") to slip in and finish before the booking committed (at " + holdCompletedAt.get()
                        + ") — if it didn't, this run failed to reproduce the race window at all")

        val finalSession = db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.findById(c, tenant.tenantId, session.resourcePoolSessionId) }.orElseThrow()
        assertEquals("cancelled", finalSession.status)

        val finalReservation = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId, holdReservation.get().reservationId) }
                .orElseThrow()
        assertEquals("held", finalReservation.status,
                "the orphaned-reservation anomaly: a 'held' reservation now exists for a session that is "
                        + "already 'cancelled' — exactly what the FOR UPDATE fix exists to make impossible")
    }

    private fun sleepForTest(millis: Int) {
        try {
            Thread.sleep(millis.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
