package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class ResourceUnitRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: ResourceUnitRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_returnsPersistedSeat() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)

        val unit = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, pool.resourcePoolId, "A1", "A", 1) }

        assertNotNull(unit.resourceUnitId)
        assertEquals("A1", unit.label)
        assertEquals("A", unit.groupKey)
        assertEquals(1, unit.sequenceNo)
        assertTrue(unit.isActive)
    }

    @Test
    fun findById_returnsRow() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "B2", "B", 2)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, unit.resourceUnitId) }

        assertTrue(found.isPresent)
        assertEquals("B2", found.get().label)
    }

    @Test
    fun findByPool_returnsSeatsOrderedByRowThenSequence() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        fixture.unit(tenant, pool, "A2", "A", 2)
        fixture.unit(tenant, pool, "A1", "A", 1)
        fixture.unit(tenant, pool, "B1", "B", 1)

        val seats = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findByPool(c, tenant.tenantId, pool.resourcePoolId) }

        assertEquals(3, seats.size)
        assertEquals(listOf("A1", "A2", "B1"), seats.map(ResourceUnit::label))
    }
}
