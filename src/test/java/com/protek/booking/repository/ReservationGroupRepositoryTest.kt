package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class ReservationGroupRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: ReservationGroupRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_returnsPendingGroup() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val customer = fixture.customer(tenant)

        val group = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, pool.resourcePoolId, customer.customerId, 2) }

        assertNotNull(group.reservationGroupId)
        assertEquals("pending", group.status)
        assertEquals(2, group.requestedUnitCount)
        assertNull(group.confirmedAt)
    }

    @Test
    fun findById_returnsRow() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, group.reservationGroupId) }

        assertTrue(found.isPresent)
    }

    @Test
    fun markConfirmed_setsStatusAndTimestamp() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.markConfirmed(c, tenant.tenantId, group.reservationGroupId) }

        assertEquals(1, updated)
        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, group.reservationGroupId) }.orElseThrow()
        assertEquals("confirmed", found.status)
        assertNotNull(found.confirmedAt)
    }

    @Test
    fun markFailed_setsStatusAndFailedUnit() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val customer = fixture.customer(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val group = fixture.group(tenant, pool, customer, 1)

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.markFailed(c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId) }

        assertEquals(1, updated)
        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, group.reservationGroupId) }.orElseThrow()
        assertEquals("failed", found.status)
        assertEquals(unit.resourceUnitId, found.failedResourceUnitId)
    }

    @Test
    fun markCancelled_setsStatus() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.markCancelled(c, tenant.tenantId, group.reservationGroupId) }

        assertEquals(1, updated)
        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, group.reservationGroupId) }.orElseThrow()
        assertEquals("cancelled", found.status)
    }
}
