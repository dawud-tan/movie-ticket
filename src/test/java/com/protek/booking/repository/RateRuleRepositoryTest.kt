package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

@QuarkusTest
class RateRuleRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: RateRuleRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_roundTripsJsonbRuleDefinition() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)

        val rule = db.inTenantTransaction(tenant.tenantId) { c -> repo.insert(c, tenant.tenantId,
                pool.resourcePoolId, "{\"type\":\"flat\",\"amount\":50000}", Instant.now().minusSeconds(60)) }

        assertNotNull(rule.rateRuleId)
        assertTrue(rule.ruleDefinitionJson!!.contains("50000"))
    }

    @Test
    fun findActive_returnsRuleWithinEffectiveWindow() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        fixture.rateRule(tenant, pool, BigDecimal("75000"))

        val active = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findActive(c, tenant.tenantId, pool.resourcePoolId) }

        assertTrue(active.isPresent)
        assertTrue(active.get().ruleDefinitionJson!!.contains("75000"))
    }

    @Test
    fun findActive_noRuleConfigured_returnsEmpty() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)

        val active = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findActive(c, tenant.tenantId, pool.resourcePoolId) }

        assertTrue(active.isEmpty)
    }
}
