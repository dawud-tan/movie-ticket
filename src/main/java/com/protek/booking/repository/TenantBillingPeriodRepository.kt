package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.TenantBillingPeriod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/** Platform-to-tenant billing. Tenant-scoped and under RLS, so cross-tenant billing
 * work iterates tenants and sets context per tenant rather than issuing one global query —
 * see `BillingService`. */
@ApplicationScoped
class TenantBillingPeriodRepository {

    @Inject
    lateinit var db: Db

    /**
     * The `UNIQUE (tenant_id, period_start, period_end)` constraint from V1 is what
     * makes generation safe to re-run: `ON CONFLICT DO NOTHING` means a monthly job
     * that fires twice, or a manual re-run after a partial failure, cannot produce two
     * invoices for the same month. Returns empty when the row already existed.
     */
    fun insertIfAbsent(c: Connection, tenantId: UUID?,
                       periodStart: LocalDate?, periodEnd: LocalDate?,
                       planTier: String?, amountDue: BigDecimal?,
                       currency: String?): Optional<TenantBillingPeriod> =
        db.queryOne(c,
            "INSERT INTO tenant_billing_period " +
                    "(tenant_id, period_start, period_end, plan_tier, amount_due, currency) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (tenant_id, period_start, period_end) DO NOTHING " +
                    "RETURNING " + COLUMNS,
            ::map, tenantId, periodStart, periodEnd, planTier, amountDue, currency)

    fun findByTenant(c: Connection, tenantId: UUID?): List<TenantBillingPeriod> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM tenant_billing_period WHERE tenant_id = ? " +
                    "ORDER BY period_start DESC",
            ::map, tenantId)

    fun find(c: Connection, tenantId: UUID?,
             periodStart: LocalDate?, periodEnd: LocalDate?): Optional<TenantBillingPeriod> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM tenant_billing_period " +
                    "WHERE tenant_id = ? AND period_start = ? AND period_end = ?",
            ::map, tenantId, periodStart, periodEnd)

    fun findByStatus(c: Connection, tenantId: UUID?, status: String?): List<TenantBillingPeriod> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM tenant_billing_period " +
                    "WHERE tenant_id = ? AND status = ? ORDER BY period_start",
            ::map, tenantId, status)

    /** The manual invoicing step: an invoice was actually sent. Guarded on
     * `status = 'pending'` so re-sending can't rewind a period that was already
     * paid. */
    fun markInvoiced(c: Connection, tenantId: UUID?, periodId: UUID?): Int =
        db.execute(c,
            "UPDATE tenant_billing_period SET status = 'invoiced', invoiced_at = now() " +
                    "WHERE tenant_id = ? AND tenant_billing_period_id = ? AND status = 'pending'",
            tenantId, periodId)

    /** A bank transfer arrived and was recorded against `payment_reference` — a
     * legitimate, sufficient answer at low tenant counts. */
    fun markPaid(c: Connection, tenantId: UUID?, periodId: UUID?, paymentReference: String?): Int =
        db.execute(c,
            "UPDATE tenant_billing_period SET status = 'paid', paid_at = now(), " +
                    "payment_reference = ? WHERE tenant_id = ? AND tenant_billing_period_id = ? " +
                    "AND status IN ('invoiced','overdue')",
            paymentReference, tenantId, periodId)

    /** Marks invoiced-but-unpaid periods overdue once their grace window has passed — the
     * input to the `tenant.status → suspended` enforcement, kept as a separate
     * status so "nobody has chased this yet" and "chased and still unpaid" stay
     * distinguishable. */
    fun markOverdue(c: Connection, tenantId: UUID?, invoicedBefore: LocalDate?): Int =
        db.execute(c,
            "UPDATE tenant_billing_period SET status = 'overdue' " +
                    "WHERE tenant_id = ? AND status = 'invoiced' AND invoiced_at < ?",
            tenantId, invoicedBefore!!.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))

    private fun map(rs: ResultSet): TenantBillingPeriod {
        val invoicedAt = rs.getTimestamp("invoiced_at")
        val paidAt = rs.getTimestamp("paid_at")
        return TenantBillingPeriod(
            rs.getObject("tenant_billing_period_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("period_start", LocalDate::class.java),
            rs.getObject("period_end", LocalDate::class.java),
            rs.getString("plan_tier"),
            rs.getBigDecimal("amount_due"),
            rs.getString("currency"),
            rs.getString("status"),
            invoicedAt?.toInstant(),
            paidAt?.toInstant(),
            rs.getString("payment_reference"),
        )
    }

    private companion object {
        const val COLUMNS =
            "tenant_billing_period_id, tenant_id, period_start, period_end, plan_tier, " +
                    "amount_due, currency, status, invoiced_at, paid_at, payment_reference"
    }
}
