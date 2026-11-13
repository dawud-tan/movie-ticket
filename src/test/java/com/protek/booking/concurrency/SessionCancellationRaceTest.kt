package com.protek.booking.concurrency

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CatalogService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The third concurrency scenario: one request books a seat in a session mid-`on_sale`;
 * a second, delay-injected to land between the booking's status-read and its
 * insert, cancels that same session. The fix — `SELECT ... FOR UPDATE` instead of a
 * plain read (implemented in [BookingService.hold]) — forces the cancellation
 * to block until the booking's transaction resolves, rather than slipping its write in
 * between the booking's read and its insert. The observable proof is ordering, not final
 * state: since both a legitimate "booking won the race" and "cancellation won the race"
 * outcome leave the same *kind* of final row states, what actually distinguishes correct
 * from broken is whether the cancellation's write was provably forced to wait.
 *
 * The differential counterpart, [SessionCancellationRaceDifferentialTest], reverts
 * to a plain `SELECT` for the same race and confirms the cancellation is no longer
 * blocked — landing before the booking commits, exactly the orphaned-reservation anomaly
 * this fix exists to close.
 */
@QuarkusTest
@TestProfile(RaceDelayTestProfile::class)
class SessionCancellationRaceTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var dataSource: AgroalDataSource

    @Test
    fun cancellationDuringHold_blocksUntilHoldCommits_neverSlipsInBetween() {
        // BookingService/CatalogService resolve their tenant from CurrentTenant (one tenant
        // per deployment) rather than accepting one — so fixtures
        // exercised through the service layer (unlike the repository-level concurrency
        // test) must be built under that same configured tenant, not a fresh random one.
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        // The demo-cinema tenant already has a "cancellation_policy" v1 from the demo seed
        // migration (policy_document is unique per tenant+doc_type+version, tenant-wide,
        // not per-hall) — reuse it rather than colliding with a duplicate insert.
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val holdResult = AtomicReference<BookingService.HoldResult>()
        val holdStartedAt = AtomicReference<Instant>()
        val holdCompletedAt = AtomicReference<Instant>()
        val cancelCompletedAt = AtomicReference<Instant>()
        val holdError = AtomicReference<Exception>()
        val cancelError = AtomicReference<Exception>()

        val bookingThread = Thread({
            try {
                holdStartedAt.set(Instant.now())
                // java.util.List.of, as the Java had it: it refuses a null id where listOf would not.
                val result = bookingService.hold(pool.resourcePoolId, session.resourcePoolSessionId,
                        java.util.List.of(unit.resourceUnitId), "Race Customer", "+62800000001",
                        AgeClassification.Attestation.NOT_TICKED)
                holdResult.set(result)
                holdCompletedAt.set(Instant.now())
            } catch (e: Exception) {
                holdError.set(e)
            }
        }, "booking-thread")

        val cancelThread = Thread({
            try {
                // Head start for the booking thread: by the time this fires, it must
                // already be past its FOR UPDATE read and into the injected sleep —
                // otherwise this isn't testing "lands between read and insert" at all.
                Thread.sleep(400)
                catalogService.updateShowingStatus(session.resourcePoolSessionId, "cancelled")
                cancelCompletedAt.set(Instant.now())
            } catch (e: Exception) {
                cancelError.set(e)
            }
        }, "cancel-thread")

        bookingThread.start()
        cancelThread.start()
        // Inside the booking's window, and after the cancel has queued behind it: the cancel's
        // own UPDATE once more, run where it can say why it waits. Asserted after the joins.
        Thread.sleep(700)
        val probe = cancelUpdateUnderLockTimeout(tenant.tenantId, session.resourcePoolSessionId)
        bookingThread.join(20_000)
        cancelThread.join(20_000)

        assertNull(holdError.get(), "booking thread threw: " + holdError.get())
        assertNull(cancelError.get(), "cancel thread threw: " + cancelError.get())
        assertNotNull(holdCompletedAt.get())
        assertNotNull(cancelCompletedAt.get())

        assertTrue(holdResult.get().success, "the booking started while the session was genuinely on_sale, so it must succeed")

        // The direct proof, which the timing below cannot give: mid-window, the cancel's UPDATE
        // was waiting on a lock. 55P03 is lock_timeout expiring, which only a lock wait raises, so
        // a cancel held up some other way (a slow pool, a pause) cannot pass for one. The timing
        // alone would accept that, with the session lock gone.
        assertEquals("55P03", probe?.sqlState(),
                "the cancel's UPDATE, tried 700 ms into the booking's " + RaceDelayTestProfile.RACE_DELAY_MS
                        + " ms window, must be waiting on the session row lock; it "
                        + (if (probe == null) "went straight through, so nothing held the row"
                           else "failed for another reason: " + probe.message))

        // The proof that FOR UPDATE actually serialized the two transactions: the
        // cancellation's UPDATE could only have completed once the booking's row lock was
        // released at commit, which is after the booking's injected sleep. Unblocked, it would
        // finish ~400 ms after the booking started. Not "cancel finished after hold returned":
        // hold() runs notifySeatMap's transaction after its commit, while the cancel is released
        // at the commit itself, so run alone the cancel finished 0.5–2.1 ms first in four runs
        // out of four, each taking 2.2–2.8 s, i.e. ones where the lock plainly held.
        // The same fix as SessionCancellationRaceIT's.
        val earliestBlockedFinish = holdStartedAt.get().plusMillis(RaceDelayTestProfile.RACE_DELAY_MS)
        assertFalse(cancelCompletedAt.get().isBefore(earliestBlockedFinish),
                "cancellation completed at " + cancelCompletedAt.get() + ", before the booking's "
                        + RaceDelayTestProfile.RACE_DELAY_MS + " ms race window ended at " + earliestBlockedFinish
                        + " — it was not blocked by the session row lock")

        val finalSession = db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.findById(c, tenant.tenantId, session.resourcePoolSessionId) }.orElseThrow()
        assertEquals("cancelled", finalSession.status)

        val reservation = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId, holdResult.get().reservations[0].reservationId) }
                .orElseThrow()
        // This used to be "held": the cancel set the status and nothing else, which left
        // a live booking on a dead showing. The cancel now takes the
        // seats with it, and the order of the two is still the point: the hold committed first,
        // so the cancel saw it and cancelled it, rather than the hold landing on a cancelled showing.
        assertEquals("cancelled", reservation.status,
                "a booking that beat the cancellation is cancelled by it, not orphaned on a dead showing")
        assertEquals(BookingService.SHOWING_CANCELLED, reservation.cancellationReason)
    }

    /** `CatalogService.updateShowingStatus`'s UPDATE on a connection that gives up after 500 ms
     * of waiting for a lock, the way `AuditChainConcurrencyTest` probes the audit-chain lock. Always rolled
     * back, so it never cancels anything. Returns what it threw, or null if it went through.
     * 700 + 500 ms ends well before the booking's 2000 ms window does. */
    private fun cancelUpdateUnderLockTimeout(tenantId: UUID, sessionId: UUID?): DataAccessException? {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
                    ps.setString(1, tenantId.toString())
                    ps.execute()
                }
                c.prepareStatement("SET LOCAL lock_timeout = '500ms'").use { ps -> ps.execute() }
                sessionRepo.updateStatus(c, tenantId, sessionId, "cancelled")
                return null
            } catch (e: DataAccessException) {
                return e
            } finally {
                c.rollback()
            }
        }
    }
}
