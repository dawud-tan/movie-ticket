package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.repository.AuditLogRepository
import com.protek.booking.repository.TenantRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.util.HexFormat
import java.util.UUID

/**
 * Pins each tenant's audit chain outside the operator's reach: hourly, the newest unanchored
 * row's row_hash goes to a timestamp authority and its signed reply is kept in audit_anchor
 * (V1 says what that proves). An hour is the longest stretch of history the operator could still
 * rewrite unseen; a tenant with no new row since its last anchor is skipped, not re-stamped.
 */
@ApplicationScoped
class AuditAnchorService {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var tenantRepository: TenantRepository
    @Inject
    lateinit var auditRepo: AuditLogRepository
    @Inject
    lateinit var tsa: TimestampAuthority

    @Scheduled(every = "{booking.audit-anchor.interval}", delayed = "1m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun anchorAll() {
        if (!tsa.enabled) return
        // tenant carries no RLS policy, so listing it needs no tenant context (as the sweeps do).
        for (tenant in db.inTransaction(tenantRepository::findAll)) {
            try {
                anchor(tenant.tenantId)
            } catch (e: Exception) {
                // One tenant's failure, or the authority being down, must not stop the others;
                // the next run anchors the same rows and more.
                LOG.warnf(e, "Audit anchor failed for tenant %s; retrying next run", tenant.tenantId)
            }
        }
    }

    /** The audit_id it anchored, or null when nothing was written since the last anchor. */
    fun anchor(tenantId: UUID): Long? {
        val head = db.inTenantTransaction(tenantId) { c -> auditRepo.unanchoredHead(c, tenantId) } ?: return null
        // Outside any transaction: a slow authority must not hold a connection.
        val reply = tsa.stamp(HexFormat.of().parseHex(head.rowHash))
        db.inTenantTransaction(tenantId) { c -> auditRepo.insertAnchor(c, tenantId, head.auditId, reply) }
        return head.auditId
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(AuditAnchorService::class.java)
    }
}
