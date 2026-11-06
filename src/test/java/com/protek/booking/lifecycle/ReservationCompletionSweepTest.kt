package com.protek.booking.lifecycle

import com.protek.booking.db.Db
import com.protek.booking.domain.PolicyDocument
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.PolicyDocumentRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The `confirmed → completed` transition (the lifecycle's
 * `confirmed --> completed : showing date has passed`).
 *
 * Before this existed, `'completed'` was a status the schema permitted, the plan
 * specified, and nothing ever produced — so these tests are as much about proving the status
 * is now reachable at all as about the boundary conditions around it.
 */
@QuarkusTest
class ReservationCompletionSweepTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var policyRepo: PolicyDocumentRepository

    /**
     * policy_document is UNIQUE on (tenant_id, doc_type, version) tenant-wide, so a test that
     * builds two reservations for one tenant cannot call TestFixture.policy() twice — it always
     * inserts version 1. Resolve-or-create keeps each test's setup independent of how many
     * reservations it happens to need.
     */
    private fun policyFor(tenant: Tenant): PolicyDocument =
        db.inTenantTransaction(tenant.tenantId) { c ->
                    policyRepo.findLatest(c, tenant.tenantId, "cancellation_policy") }
                .orElseGet { fixture.policy(tenant) }

    /**
     * A confirmed reservation whose period is entirely in the past.
     *
     * Built by inserting the hold with explicit past instants rather than via
     * `TestFixture.session`, which always creates a showing a day out: the whole point
     * here is a period that has already ended, and the `EXCLUDE` constraint is
     * indifferent to whether a range sits in the past.
     */
    private fun confirmedReservation(tenant: Tenant, pool: ResourcePool, unit: ResourceUnit,
                                     periodStart: Instant, periodEnd: Instant): Reservation {
        val session = db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, "Showing",
                    periodStart, periodEnd, periodStart.minusSeconds(900), "on_sale") }
        val customer = fixture.customer(tenant)
        val policy = policyFor(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val held = db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId,
                session.resourcePoolSessionId, policy.policyDocumentId, periodStart, periodEnd,
                PRICE, "IDR", Instant.now().plus(Duration.ofMinutes(10)), "customer", UUID.randomUUID()) }
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.confirm(c, tenant.tenantId, held.reservationId) }
        return held
    }

    private fun statusOf(tenant: Tenant, reservation: Reservation): String? =
        db.inTenantTransaction(tenant.tenantId) { c ->
                    reservationRepo.findById(c, tenant.tenantId, reservation.reservationId) }
                .orElseThrow().status

    private fun sweep(tenant: Tenant): Int =
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.completePastShowings(c, tenant.tenantId) }

    @Test
    fun confirmedReservationWhoseShowingHasPassed_becomesCompleted() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val start = Instant.now().minus(Duration.ofDays(2))
        val reservation = confirmedReservation(tenant, pool, unit, start, start.plus(Duration.ofHours(2)))

        assertEquals("confirmed", statusOf(tenant, reservation))
        assertEquals(1, sweep(tenant))
        assertEquals("completed", statusOf(tenant, reservation),
                "a reservation for a showing that has finished must reach 'completed' — the "
                        + "status the schema permitted and nothing previously ever set")
    }

    @Test
    fun confirmedReservationForAFutureShowing_isLeftAlone() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "B1", "B", 1)
        val start = Instant.now().plus(Duration.ofDays(1))
        val reservation = confirmedReservation(tenant, pool, unit, start, start.plus(Duration.ofHours(2)))

        assertEquals(0, sweep(tenant), "nothing to complete")
        assertEquals("confirmed", statusOf(tenant, reservation))
    }

    @Test
    fun aShowingStillInProgress_isNotCompletedUntilItEnds() {
        // The boundary that matters operationally: the sweep keys on the END of the period, not
        // its start. Completing a booking mid-showing would mark it attended while the audience
        // is still in the room.
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "C1", "C", 1)
        val start = Instant.now().minus(Duration.ofMinutes(30))
        val reservation = confirmedReservation(tenant, pool, unit, start, start.plus(Duration.ofHours(2)))

        assertEquals(0, sweep(tenant))
        assertEquals("confirmed", statusOf(tenant, reservation))
    }

    @Test
    fun heldCancelledAndExpiredRowsWithPastPeriods_areNotCompleted() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val start = Instant.now().minus(Duration.ofDays(2))
        val end = start.plus(Duration.ofHours(2))

        // A hold nobody ever paid for: belongs to the expiry sweep. Completing it would record
        // an attended booking that never existed.
        val heldUnit = fixture.unit(tenant, pool, "D1", "D", 1)
        val session = db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, "Past showing",
                    start, end, start.minusSeconds(900), "on_sale") }
        val customer = fixture.customer(tenant)
        val policy = policyFor(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val stillHeld = db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, heldUnit.resourceUnitId,
                session.resourcePoolSessionId, policy.policyDocumentId, start, end,
                PRICE, "IDR", Instant.now().plus(Duration.ofMinutes(10)), "customer", UUID.randomUUID()) }

        // A cancelled booking does not become attended because time passed.
        val cancelledUnit = fixture.unit(tenant, pool, "D2", "D", 2)
        val cancelled = confirmedReservation(tenant, pool, cancelledUnit, start, end)
        db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.cancel(c, tenant.tenantId, cancelled.reservationId, "test") }

        assertEquals(0, sweep(tenant), "only 'confirmed' rows transition")
        assertEquals("held", statusOf(tenant, stillHeld))
        assertEquals("cancelled", statusOf(tenant, cancelled))
    }

    @Test
    fun completionIsIdempotent_soARepeatedSweepIsANoOp() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "E1", "E", 1)
        val start = Instant.now().minus(Duration.ofDays(3))
        val reservation = confirmedReservation(tenant, pool, unit, start, start.plus(Duration.ofHours(2)))

        assertEquals(1, sweep(tenant))
        // The sweep runs every 30s by default; a second pass must find nothing, or every run
        // would rewrite the same rows and emit a fresh audit entry each time.
        assertEquals(0, sweep(tenant))
        assertEquals("completed", statusOf(tenant, reservation))
    }

    @Test
    fun completionIsScopedToOneTenant() {
        // The property the multi-tenant sweep depends on: RLS plus the explicit tenant filter
        // mean sweeping tenant A cannot touch tenant B's rows. Without it, the per-tenant loop
        // would complete every tenant's reservations on the first iteration.
        val tenantA = fixture.tenant()
        val poolA = fixture.pool(tenantA)
        val unitA = fixture.unit(tenantA, poolA, "F1", "F", 1)
        val tenantB = fixture.tenant()
        val poolB = fixture.pool(tenantB)
        val unitB = fixture.unit(tenantB, poolB, "F1", "F", 1)

        val start = Instant.now().minus(Duration.ofDays(2))
        val end = start.plus(Duration.ofHours(2))
        val a = confirmedReservation(tenantA, poolA, unitA, start, end)
        val b = confirmedReservation(tenantB, poolB, unitB, start, end)

        assertEquals(1, sweep(tenantA), "sweeping tenant A must affect exactly its own one row")
        assertEquals("completed", statusOf(tenantA, a))
        assertEquals("confirmed", statusOf(tenantB, b),
                "tenant B's reservation must be untouched by a sweep scoped to tenant A")

        assertEquals(1, sweep(tenantB))
        assertEquals("completed", statusOf(tenantB, b))
    }

    @Test
    fun completingAReservationReleasesItsSeatFromTheExclusionConstraint() {
        // Why this transition is more than bookkeeping: reservation_no_overlap filters on
        // status IN ('held','confirmed'), so a confirmed past booking stayed a live claim on its
        // seat forever. Once completed, the same seat and period can be claimed again — which is
        // what makes the constraint's partial index shed settled history rather than grow
        // without bound.
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "G1", "G", 1)
        val start = Instant.now().minus(Duration.ofDays(2))
        val end = start.plus(Duration.ofHours(2))
        confirmedReservation(tenant, pool, unit, start, end)

        assertEquals(1, sweep(tenant))

        // Re-claiming the identical seat and period now succeeds, which it could not have done
        // while the old row was still 'confirmed'.
        val customer = fixture.customer(tenant)
        val policy = policyFor(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val session = db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, "Rerun",
                    start, end, start.minusSeconds(900), "on_sale") }
        assertDoesNotThrow { db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId,
                session.resourcePoolSessionId, policy.policyDocumentId, start, end,
                PRICE, "IDR", Instant.now().plus(Duration.ofMinutes(10)), "customer", UUID.randomUUID()) } }
    }

    companion object {
        private val PRICE = BigDecimal("50000.00")
    }
}
