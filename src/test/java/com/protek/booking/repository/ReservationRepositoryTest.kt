package com.protek.booking.repository

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@QuarkusTest
class ReservationRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: ReservationRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insertHold_returnsPersistedHeldReservation() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val holdExpiresAt = Instant.now().plusSeconds(600)

        val r = db.inTenantTransaction(tenant.tenantId) { c -> repo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId,
                session.resourcePoolSessionId, policy.policyDocumentId, session.startsAt, session.endsAt,
                PRICE, "IDR", holdExpiresAt, "customer", UUID.randomUUID()) }

        assertNotNull(r.reservationId)
        assertEquals("held", r.status)
        assertEquals(0, PRICE.compareTo(r.priceAmount!!))
        assertEquals(session.startsAt, r.periodStart)
        assertEquals(session.endsAt, r.periodEnd)
    }

    @Test
    fun insertHold_overlappingSameUnit_throwsExclusionViolation() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group1 = fixture.group(tenant, pool, customer, 1)
        val group2 = fixture.group(tenant, pool, customer, 1)

        fixture.reservation(tenant, group1, unit, session, policy, PRICE)

        val ex = assertThrows(DataAccessException::class.java) {
            db.inTenantTransaction(tenant.tenantId) { c -> repo.insertHold(
                    c, tenant.tenantId, group2.reservationGroupId, unit.resourceUnitId,
                    session.resourcePoolSessionId, policy.policyDocumentId, session.startsAt, session.endsAt,
                    PRICE, "IDR", Instant.now().plusSeconds(600), "customer", UUID.randomUUID()) } }

        assertTrue(ex.isExclusionViolation(), "expected 23P01, got sqlState=" + ex.sqlState())
    }

    @Test
    fun findById_returnsRow() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val created = fixture.reservation(tenant, group, unit, session, policy, PRICE)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, created.reservationId) }

        assertTrue(found.isPresent)
        assertEquals(created.reservationId, found.get().reservationId)
    }

    @Test
    fun findByGroup_returnsAllReservationsInGroup() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unitA = fixture.unit(tenant, pool, "A1", "A", 1)
        val unitB = fixture.unit(tenant, pool, "A2", "A", 2)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 2)
        fixture.reservation(tenant, group, unitA, session, policy, PRICE)
        fixture.reservation(tenant, group, unitB, session, policy, PRICE)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findByGroup(c, tenant.tenantId, group.reservationGroupId) }

        assertEquals(2, found.size)
    }

    @Test
    fun findSeatMap_showsHeldSeatAndAvailableSeat() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val heldUnit = fixture.unit(tenant, pool, "A1", "A", 1)
        val availableUnit = fixture.unit(tenant, pool, "A2", "A", 2)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        fixture.reservation(tenant, group, heldUnit, session, policy, PRICE)

        val seatMap = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findSeatMap(c, tenant.tenantId, pool.resourcePoolId, session.resourcePoolSessionId) }

        assertEquals(2, seatMap.size)
        val held = seatMap.first { s -> s.resourceUnitId == heldUnit.resourceUnitId }
        val available = seatMap.first { s -> s.resourceUnitId == availableUnit.resourceUnitId }
        assertEquals("held", held.seatStatus)
        assertNotNull(held.reservationId)
        assertEquals("available", available.seatStatus)
        assertNull(available.reservationId)
    }

    @Test
    fun confirm_transitionsHeldToConfirmed() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val created = fixture.reservation(tenant, group, unit, session, policy, PRICE)

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.confirm(c, tenant.tenantId, created.reservationId) }

        assertEquals(1, updated)
        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, created.reservationId) }.orElseThrow()
        assertEquals("confirmed", found.status)
        assertNotNull(found.confirmedAt)
    }

    @Test
    fun confirm_alreadyConfirmed_isNoOp() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val created = fixture.reservation(tenant, group, unit, session, policy, PRICE)
        db.inTenantTransaction(tenant.tenantId) { c -> repo.confirm(c, tenant.tenantId, created.reservationId) }

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.confirm(c, tenant.tenantId, created.reservationId) }

        assertEquals(0, updated, "confirm() only matches status='held', so a second call must affect zero rows")
    }

    @Test
    fun cancel_transitionsHeldToCancelled_andFreesTheSeat() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val created = fixture.reservation(tenant, group, unit, session, policy, PRICE)

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.cancel(c, tenant.tenantId, created.reservationId, "customer request") }

        assertEquals(1, updated)
        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, created.reservationId) }.orElseThrow()
        assertEquals("cancelled", found.status)
        assertEquals("customer request", found.cancellationReason)

        // the EXCLUDE constraint's WHERE clause only matches held/confirmed, so the same
        // seat/period should now be free to re-hold
        val rebooked = fixture.reservation(tenant, fixture.group(tenant, pool, customer, 1), unit, session, policy, PRICE)
        assertEquals("held", rebooked.status)
    }

    @Test
    fun expireStaleHolds_expiresOnlyPastDeadlineHolds() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val staleUnit = fixture.unit(tenant, pool, "A1", "A", 1)
        val freshUnit = fixture.unit(tenant, pool, "A2", "A", 2)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 2)

        val stale = db.inTenantTransaction(tenant.tenantId) { c -> repo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, staleUnit.resourceUnitId,
                session.resourcePoolSessionId, policy.policyDocumentId, session.startsAt, session.endsAt,
                PRICE, "IDR", Instant.now().minusSeconds(5), "customer", UUID.randomUUID()) }
        val fresh = fixture.reservation(tenant, group, freshUnit, session, policy, PRICE)

        val expired = db.inTenantTransaction(tenant.tenantId) { c -> repo.expireStaleHolds(c, tenant.tenantId) }

        assertTrue(expired >= 1)
        val staleFound = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, stale.reservationId) }.orElseThrow()
        val freshFound = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, fresh.reservationId) }.orElseThrow()
        assertEquals("expired", staleFound.status)
        assertEquals("held", freshFound.status)
    }

    companion object {
        private val PRICE = BigDecimal("50000.00")
    }
}
