package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class PolicyDocumentRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: PolicyDocumentRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_returnsPersistedDocument() {
        val tenant = fixture.tenant()

        val doc = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "cancellation_policy", 1, "Refundable up to 1 hour before.",
                    Instant.now().minusSeconds(60)) }

        assertNotNull(doc.policyDocumentId)
        assertEquals(1, doc.version)
    }

    @Test
    fun findLatest_returnsHighestVersionAmongEffectiveDocuments() {
        val tenant = fixture.tenant()
        db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "cancellation_policy", 1, "v1", Instant.now().minusSeconds(7200)) }
        db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "cancellation_policy", 2, "v2", Instant.now().minusSeconds(3600)) }
        // not yet effective — must not be selected as "latest"
        db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "cancellation_policy", 3, "v3-future", Instant.now().plusSeconds(3600)) }

        val latest = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findLatest(c, tenant.tenantId, "cancellation_policy") }

        assertTrue(latest.isPresent)
        assertEquals(2, latest.get().version)
    }

    @Test
    fun findById_returnsRow() {
        val tenant = fixture.tenant()
        val doc = fixture.policy(tenant)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, doc.policyDocumentId) }

        assertTrue(found.isPresent)
        assertEquals(doc.policyDocumentId, found.get().policyDocumentId)
    }
}
