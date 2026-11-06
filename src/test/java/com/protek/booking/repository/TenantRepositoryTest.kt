package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Runs against a real, Flyway-migrated PostgreSQL 18 instance via Quarkus Dev Services
 * (Testcontainers) — not a mock. */
@QuarkusTest
class TenantRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: TenantRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun findBySlug_returnsInsertedTenant() {
        val tenant = fixture.tenant()

        val found = db.inTransaction { c -> repo.findBySlug(c, tenant.slug) }

        assertTrue(found.isPresent)
        assertEquals(tenant.tenantId, found.get().tenantId)
        assertEquals(tenant.slug, found.get().slug)
        assertEquals("active", found.get().status)
    }

    @Test
    fun findBySlug_unknownSlug_returnsEmpty() {
        val found = db.inTransaction { c -> repo.findBySlug(c, "does-not-exist-" + UUID.randomUUID()) }
        assertTrue(found.isEmpty)
    }

    @Test
    fun findById_returnsInsertedTenant() {
        val tenant = fixture.tenant()

        val found = db.inTransaction { c -> repo.findById(c, tenant.tenantId) }

        assertTrue(found.isPresent)
        assertEquals(tenant.slug, found.get().slug)
    }

    @Test
    fun findById_unknownId_returnsEmpty() {
        val found = db.inTransaction { c -> repo.findById(c, UUID.randomUUID()) }
        assertTrue(found.isEmpty)
    }
}
