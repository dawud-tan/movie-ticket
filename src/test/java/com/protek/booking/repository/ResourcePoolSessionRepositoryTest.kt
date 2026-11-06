package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@QuarkusTest
class ResourcePoolSessionRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: ResourcePoolSessionRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_returnsPersistedShowing() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val start = Instant.now().plus(Duration.ofDays(1))
        val end = start.plus(Duration.ofHours(2))

        val showing = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, pool.resourcePoolId, "Sat 19:00", start, end,
                    start.minusSeconds(900), "scheduled") }

        assertNotNull(showing.resourcePoolSessionId)
        assertEquals("scheduled", showing.status)
        // timestamptz stores microsecond precision and Postgres *rounds* (not truncates)
        // sub-microsecond nanos on the way in, so Instant.now()'s nanosecond remainder
        // must be rounded the same way here, not floored — otherwise a remainder >= 500ns
        // rounds up in Postgres but stays down in a naive truncatedTo(MICROS), producing a
        // rare off-by-one-microsecond mismatch.
        assertEquals(roundToMicros(start), showing.startsAt)
        assertEquals(roundToMicros(end), showing.endsAt)
    }

    @Test
    fun findById_returnsRow() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val showing = fixture.session(tenant, pool, "on_sale")

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, showing.resourcePoolSessionId) }

        assertTrue(found.isPresent)
        assertEquals("on_sale", found.get().status)
    }

    @Test
    fun findByIdForUpdate_returnsSameRowUnderLock() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val showing = fixture.session(tenant, pool, "on_sale")

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findByIdForUpdate(c, tenant.tenantId, showing.resourcePoolSessionId) }

        assertTrue(found.isPresent)
        assertEquals(showing.resourcePoolSessionId, found.get().resourcePoolSessionId)
    }

    @Test
    fun findUpcomingByPool_excludesPastAndClosedShowings() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val upcoming = fixture.session(tenant, pool, "on_sale")
        fixture.session(tenant, pool, "cancelled")

        val upcomingList = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findUpcomingByPool(c, tenant.tenantId, pool.resourcePoolId) }

        assertTrue(upcomingList.any { s -> s.resourcePoolSessionId == upcoming.resourcePoolSessionId })
        assertTrue(upcomingList.none { s -> "cancelled" == s.status })
    }

    @Test
    fun updateStatus_changesStatus() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val showing = fixture.session(tenant, pool, "scheduled")

        val updated = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.updateStatus(c, tenant.tenantId, showing.resourcePoolSessionId, "on_sale") }

        assertEquals(1, updated)
        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, showing.resourcePoolSessionId) }
        assertEquals("on_sale", found.orElseThrow().status)
    }

    companion object {
        private fun roundToMicros(instant: Instant): Instant =
            instant.plusNanos(500).truncatedTo(ChronoUnit.MICROS)
    }
}
