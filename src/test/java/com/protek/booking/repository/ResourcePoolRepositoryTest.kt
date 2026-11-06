package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class ResourcePoolRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: ResourcePoolRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_returnsPersistedRow() {
        val tenant = fixture.tenant()

        val pool = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "Hall A", "Addr", "Asia/Jakarta", "contiguous_n") }

        assertNotNull(pool.resourcePoolId)
        assertEquals("Hall A", pool.name)
        assertEquals(tenant.tenantId, pool.tenantId)
        assertEquals("contiguous_n", pool.blockClaimMode)
    }

    @Test
    fun findById_scopedToTenant_returnsRow() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, pool.resourcePoolId) }

        assertTrue(found.isPresent)
        assertEquals(pool.resourcePoolId, found.get().resourcePoolId)
    }

    @Test
    fun findById_wrongTenant_returnsEmpty() {
        val owner = fixture.tenant()
        val pool = fixture.pool(owner)
        val other = fixture.tenant()

        val found = db.inTenantTransaction(other.tenantId) { c ->
            repo.findById(c, other.tenantId, pool.resourcePoolId) }

        assertTrue(found.isEmpty)
    }

    @Test
    fun findAllByTenant_returnsOnlyThatTenantsHalls() {
        val tenant = fixture.tenant()
        val p1 = fixture.pool(tenant)
        val other = fixture.tenant()
        fixture.pool(other)

        val halls = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findAllByTenant(c, tenant.tenantId) }

        assertEquals(1, halls.size)
        assertEquals(p1.resourcePoolId, halls[0].resourcePoolId)
    }
}
