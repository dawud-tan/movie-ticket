package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.domain.TenantBillingPeriod
import com.protek.booking.repository.TenantBillingPeriodRepository
import com.protek.booking.repository.TenantRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

/**
 * Platform-to-tenant billing: a `tenant_billing_period` row generated monthly,
 * an invoice sent, a bank transfer received and recorded against `payment_reference`.
 *
 * **Flat monthly tiers, not per-ticket and not metered** — nothing here counts tickets,
 * and that is the design, not an unfinished feature. The reasoning: per-ticket billing
 * would additionally require metering every sale accurately, reconciling monthly, producing a
 * different invoice amount every period, and giving the tenant a way to trust that count —
 * "a reasonable question a cinema owner would actually ask about revenue-share billing." None
 * of it is needed to prove the concept to a first tenant, and the platform's own cloud cost
 * being usage-based does not obligate the tenant-facing price to be.
 *
 * **Why this iterates tenants instead of issuing one cross-tenant statement:**
 * `tenant_billing_period` is tenant-scoped and under FORCE ROW LEVEL SECURITY, so a
 * single `INSERT ... SELECT` across tenants could only ever see the tenant whose
 * context is set. The loop is not a workaround for RLS — it is RLS working as intended on
 * background code, the same as on request paths.
 */
@ApplicationScoped
class BillingService {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var periodRepo: TenantBillingPeriodRepository

    /**
     * Monthly price per tier, in IDR. Configuration rather than a hardcoded table because
     * tier *contents and pricing* are the decisions a first real
     * tenant conversation should inform — so the numbers must be changeable without a code
     * change, while the tier names stay fixed as a placeholder.
     */
    @ConfigProperty(name = "billing.tier.starter.monthly-idr", defaultValue = "500000")
    lateinit var starterMonthly: BigDecimal
    @ConfigProperty(name = "billing.tier.business.monthly-idr", defaultValue = "2000000")
    lateinit var businessMonthly: BigDecimal
    @ConfigProperty(name = "billing.tier.enterprise.monthly-idr", defaultValue = "7500000")
    lateinit var enterpriseMonthly: BigDecimal

    /** Days after an invoice is sent before the period is marked overdue, and after which
     * the `tenant.status → suspended` enforcement applies. */
    // A plain field: lateinit cannot hold an Int.
    @ConfigProperty(name = "billing.grace-period-days", defaultValue = "14")
    @JvmField
    final var gracePeriodDays: Int = 14

    // Nullable as the Java accepted it; a null still throws an NPE, as the Java
    // switch on a null enum did.
    fun monthlyAmountFor(tier: TenantProvisioningService.PlanTier?): BigDecimal =
        when (tier!!) {
            TenantProvisioningService.PlanTier.STARTER -> starterMonthly
            TenantProvisioningService.PlanTier.BUSINESS -> businessMonthly
            TenantProvisioningService.PlanTier.ENTERPRISE -> enterpriseMonthly
        }

    /** Opens the current calendar month's period for a newly provisioned tenant, so its
     * first invoice does not depend on the monthly job happening to run next. */
    fun openCurrentPeriod(tenant: Tenant,
                          tier: TenantProvisioningService.PlanTier?): Optional<TenantBillingPeriod> =
        openPeriod(tenant, tier, YearMonth.now(BILLING_ZONE))

    fun openPeriod(tenant: Tenant,
                   tier: TenantProvisioningService.PlanTier?,
                   month: YearMonth): Optional<TenantBillingPeriod> {
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        return db.inTenantTransaction(tenant.tenantId) { c ->
            periodRepo.insertIfAbsent(
                c, tenant.tenantId, start, end, tier!!.name, monthlyAmountFor(tier), "IDR")
        }
    }

    /**
     * Generates the current month's period for every active tenant, and marks overdue
     * anything invoiced beyond the grace window.
     *
     * Runs daily rather than monthly on purpose: a once-a-month job that fails, or falls
     * on a day the service happens to be scaled to zero, silently skips a whole billing
     * cycle and nobody notices until the revenue is missing. Daily plus the
     * `ON CONFLICT DO NOTHING` upsert makes generation self-healing — the first
     * successful run of the month creates the row and the rest are no-ops.
     *
     * Suspended and offboarding tenants are skipped: continuing to accrue charges against
     * a tenant already suspended for non-payment would compound a billing dispute rather than
     * resolve one.
     */
    // Public where the Java was package-private: Kotlin has none.
    @Scheduled(cron = "{billing.generation.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun generateMonthlyPeriods() {
        val tenants = db.inTransaction { c -> tenantRepo.findByStatus(c, Tenant.STATUS_ACTIVE) }
        val month = YearMonth.now(BILLING_ZONE)
        var created = 0
        for (tenant in tenants) {
            try {
                // The tier a tenant is actually on lives on their most recent period rather
                // than on `tenant` — tiers ship as a placeholder with no feature gating,
                // so there is deliberately no tenant.plan_tier column to disagree with the
                // billing history.
                val tier = currentTierFor(tenant)
                if (openPeriod(tenant, tier, month).isPresent) {
                    created++
                }
                markOverduePeriods(tenant)
            } catch (e: RuntimeException) {
                LOG.errorf(e, "Billing generation failed for tenant %s; continuing", tenant.slug)
            }
        }
        if (created > 0) {
            LOG.infof("Billing: opened %d period(s) for %s across %d active tenant(s)",
                created, month, tenants.size)
        }
    }

    private fun currentTierFor(tenant: Tenant): TenantProvisioningService.PlanTier {
        val history = db.inTenantTransaction(tenant.tenantId) { c ->
            periodRepo.findByTenant(c, tenant.tenantId)
        }
        return if (history.isEmpty())
            TenantProvisioningService.PlanTier.STARTER
        else
            TenantProvisioningService.PlanTier.valueOf(history[0].planTier!!)
    }

    private fun markOverduePeriods(tenant: Tenant) {
        val cutoff = LocalDate.now(BILLING_ZONE).minusDays(gracePeriodDays.toLong())
        db.inTenantTransaction(tenant.tenantId) { c -> periodRepo.markOverdue(c, tenant.tenantId, cutoff) }
    }

    fun historyFor(tenantId: UUID?): List<TenantBillingPeriod> =
        db.inTenantTransaction(tenantId) { c -> periodRepo.findByTenant(c, tenantId) }

    /** An invoice was actually sent — a manual step, which is sufficient at low tenant
     * counts. Deliberately an explicit operator action rather than something the generation
     * job does automatically: the job knows a period exists, not that an invoice left an
     * outbox. */
    fun markInvoiced(tenantId: UUID?, periodId: UUID?): Boolean =
        db.inTenantTransaction(tenantId) { c -> periodRepo.markInvoiced(c, tenantId, periodId) } > 0

    fun recordPayment(tenantId: UUID?, periodId: UUID?, paymentReference: String?): Boolean =
        db.inTenantTransaction(tenantId) { c ->
            periodRepo.markPaid(c, tenantId, periodId, paymentReference)
        } > 0

    /**
     * Non-payment enforcement: `tenant.status → suspended` past the grace
     * period, a status change on an already-existing column, not new infrastructure.
     *
     * Kept as an explicit operator action rather than wired into the daily job, because
     * suspending a paying customer's cinema over a bank-transfer timing mismatch is not a
     * decision to automate at one-tenant scale — the mechanism is what is owed, not the
     * automation.
     */
    fun suspendForNonPayment(tenantId: UUID?): Boolean {
        val overdue = db.inTenantTransaction(tenantId) { c ->
            periodRepo.findByStatus(c, tenantId, "overdue")
        }
        if (overdue.isEmpty()) {
            return false
        }
        db.inTransaction { c -> tenantRepo.updateStatus(c, tenantId, Tenant.STATUS_SUSPENDED) }
        LOG.warnf("Suspended tenant %s for non-payment (%d overdue period(s))", tenantId, overdue.size)
        return true
    }

    fun reactivate(tenantId: UUID?): Boolean =
        db.inTransaction { c -> tenantRepo.updateStatus(c, tenantId, Tenant.STATUS_ACTIVE) } > 0

    private companion object {
        private val LOG: Logger = Logger.getLogger(BillingService::class.java)

        /** Billing months are calendar months in the tenant's jurisdiction; every
         * tenant is Indonesia-domiciled, so Jakarta is the calendar that decides
         * which month "now" falls in. */
        private val BILLING_ZONE: ZoneId = ZoneId.of("Asia/Jakarta")
    }
}
