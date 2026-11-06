package com.protek.booking.tenancy

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.TenantBillingPeriodRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.service.BillingService
import com.protek.booking.service.TenantProvisioningService
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * The first billing cycle, exercised for real: generate a period, send an invoice, record a
 * bank transfer, and suspend past the grace window.
 *
 * The ask is "first `tenant_billing_period` rows generated and the
 * manual-invoicing process exercised for real" — so these tests walk the whole status
 * progression rather than only asserting a row appears, because the failure that matters is a
 * period stuck in one state with no way forward.
 */
@QuarkusTest
class BillingCycleTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var billingService: BillingService
    @Inject
    lateinit var provisioningService: TenantProvisioningService
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var periodRepo: TenantBillingPeriodRepository

    private fun provisionTenant(tier: TenantProvisioningService.PlanTier): Tenant {
        val slug = "bill-" + UUID.randomUUID()
        return provisioningService.provision(TenantProvisioningService.ProvisionRequest(
                slug, "Billing " + slug, "live_entertainment",
                Tenant.MODE_SHARED, Tenant.MODE_SHARED, "subdomain",
                null, null, null, "ID", "+62-21-5550300",
                slug + "-staff@example.test", "Owner", slug + "-owner@example.test",
                null, tier)).tenant
    }

    @Test
    fun generation_isIdempotent_soARepeatedRunCannotDoubleInvoiceAMonth() {
        val tenant = provisionTenant(TenantProvisioningService.PlanTier.STARTER)
        val month = YearMonth.now(ZoneId.of("Asia/Jakarta"))

        // Provisioning already opened this month. The daily job will hit the same month
        // repeatedly, which is the whole reason UNIQUE (tenant_id, period_start, period_end)
        // plus ON CONFLICT DO NOTHING is the mechanism rather than a "have I run today" flag.
        assertTrue(billingService.openPeriod(tenant, TenantProvisioningService.PlanTier.STARTER, month).isEmpty,
                "re-opening the same month must be a no-op, not a second invoice")
        assertEquals(1, billingService.historyFor(tenant.tenantId).size)
    }

    @Test
    fun tierPricing_comesFromConfiguration_notAHardcodedTable() {
        // Tiers ship as a placeholder with no feature gating precisely because pricing is
        // what a first tenant conversation informs — so the amounts must be config-driven.
        assertEquals(0, billingService.monthlyAmountFor(TenantProvisioningService.PlanTier.STARTER)
                .compareTo(BigDecimal("500000")))
        assertTrue(billingService.monthlyAmountFor(TenantProvisioningService.PlanTier.ENTERPRISE)
                .compareTo(billingService.monthlyAmountFor(TenantProvisioningService.PlanTier.STARTER)) > 0)
    }

    @Test
    fun invoiceThenPay_walksTheStatusProgression_andRecordsThePaymentReference() {
        val tenant = provisionTenant(TenantProvisioningService.PlanTier.BUSINESS)
        val period = billingService.historyFor(tenant.tenantId)[0]
        assertEquals("pending", period.status)
        assertEquals("BUSINESS", period.planTier)

        assertTrue(billingService.markInvoiced(tenant.tenantId, period.tenantBillingPeriodId))
        val invoiced = billingService.historyFor(tenant.tenantId)[0]
        assertEquals("invoiced", invoiced.status)
        assertNotNull(invoiced.invoicedAt)

        // Re-invoicing is guarded on status = 'pending', so a resent invoice cannot rewind a
        // period that has moved on.
        assertFalse(billingService.markInvoiced(tenant.tenantId, period.tenantBillingPeriodId),
                "marking an already-invoiced period invoiced again must be a no-op")

        assertTrue(billingService.recordPayment(tenant.tenantId, period.tenantBillingPeriodId,
                "BCA-TRF-20260808-0001"))
        val paid = billingService.historyFor(tenant.tenantId)[0]
        assertEquals("paid", paid.status)
        assertEquals("BCA-TRF-20260808-0001", paid.paymentReference,
                "The bank transfer is recorded against payment_reference — that reference "
                        + "is the whole audit trail for a manual invoicing process")
        assertNotNull(paid.paidAt)
    }

    @Test
    fun payment_cannotBeRecordedForAPeriodThatWasNeverInvoiced() {
        val tenant = provisionTenant(TenantProvisioningService.PlanTier.STARTER)
        val period = billingService.historyFor(tenant.tenantId)[0]
        // Guarded on status IN ('invoiced','overdue'): recording payment for a period nobody
        // invoiced would mean money matched to a bill that was never sent.
        assertFalse(billingService.recordPayment(tenant.tenantId, period.tenantBillingPeriodId, "REF"),
                "a pending period has no invoice to pay")
    }

    @Test
    fun suspension_requiresAnOverduePeriod_andIsReversible() {
        val tenant = provisionTenant(TenantProvisioningService.PlanTier.STARTER)
        val period = billingService.historyFor(tenant.tenantId)[0]

        // Nothing overdue yet: suspension must refuse rather than proceed, so it can always be
        // justified from the record afterward.
        assertFalse(billingService.suspendForNonPayment(tenant.tenantId))
        assertEquals(Tenant.STATUS_ACTIVE,
                db.inTransaction { c -> tenantRepo.findById(c, tenant.tenantId) }.orElseThrow().status)

        billingService.markInvoiced(tenant.tenantId, period.tenantBillingPeriodId)
        // Push the invoice date past the grace window rather than waiting 14 days.
        db.inTenantTransaction(tenant.tenantId) { c -> periodRepo.markOverdue(
                c, tenant.tenantId, LocalDate.now().plusDays(1)) }
        assertEquals("overdue", billingService.historyFor(tenant.tenantId)[0].status)

        assertTrue(billingService.suspendForNonPayment(tenant.tenantId))
        assertEquals(Tenant.STATUS_SUSPENDED,
                db.inTransaction { c -> tenantRepo.findById(c, tenant.tenantId) }.orElseThrow().status)

        // Suspension is a status change on an existing column, which is exactly
        // what makes reinstatement after a late payment trivial rather than a rebuild.
        assertTrue(billingService.reactivate(tenant.tenantId))
        assertEquals(Tenant.STATUS_ACTIVE,
                db.inTransaction { c -> tenantRepo.findById(c, tenant.tenantId) }.orElseThrow().status)
    }

    @Test
    fun billingPeriodsAreTenantIsolated_soOneTenantCannotSeeAnothersInvoices() {
        val a = provisionTenant(TenantProvisioningService.PlanTier.STARTER)
        val b = provisionTenant(TenantProvisioningService.PlanTier.ENTERPRISE)

        val aPeriods = billingService.historyFor(a.tenantId)
        assertTrue(aPeriods.all { p -> p.tenantId == a.tenantId },
                "tenant_billing_period is under RLS; a tenant's history must contain only its own rows")
        assertTrue(billingService.historyFor(b.tenantId)
                        .none { p -> p.tenantId == a.tenantId },
                "tenant B's billing history leaked tenant A's rows")
    }
}
