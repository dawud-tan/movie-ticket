package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.AuditLogEntry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** audit_log is read-only here: its rows are written exclusively by the audit triggers. */
@ApplicationScoped
class AuditLogRepository {

    @Inject
    lateinit var db: Db

    fun findByEntity(c: Connection, tenantId: UUID?, entityType: String?, entityId: UUID?): List<AuditLogEntry> =
        db.query(c,
            "SELECT audit_id, tenant_id, entity_type, entity_id, action, actor_type, occurred_at, " +
                    "row_hash, previous_state::text AS previous_state, new_state::text AS new_state " +
                    "FROM audit_log WHERE tenant_id = ? AND entity_type = ? AND entity_id = ? ORDER BY audit_id",
            ::map, tenantId, entityType, entityId)

    data class ChainHead(val auditId: Long, val rowHash: String)

    /** The tenant's newest row, when it is newer than the last anchor; null when nothing was
     * written since. Every older row has committed by then: audit_append's lock makes audit_id
     * order commit order. */
    fun unanchoredHead(c: Connection, tenantId: UUID): ChainHead? =
        db.queryOne(c,
            "SELECT audit_id, row_hash FROM audit_log WHERE tenant_id = ? AND audit_id > " +
                    "COALESCE((SELECT max(audit_id) FROM audit_anchor WHERE tenant_id = ?), 0) " +
                    "ORDER BY audit_id DESC LIMIT 1",
            { rs -> ChainHead(rs.getLong("audit_id"), rs.getString("row_hash")) }, tenantId, tenantId)
            .orElse(null)

    /** The one write this class makes; audit_anchor, not audit_log. */
    fun insertAnchor(c: Connection, tenantId: UUID, auditId: Long, tsaReply: ByteArray) {
        db.execute(c, "INSERT INTO audit_anchor(tenant_id, audit_id, tsa_reply) VALUES (?, ?, ?)",
                tenantId, auditId, tsaReply)
    }

    private fun map(rs: ResultSet): AuditLogEntry =
        AuditLogEntry(
            rs.getLong("audit_id"),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("entity_type"),
            rs.getObject("entity_id", UUID::class.java),
            rs.getString("action"),
            rs.getString("actor_type"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("row_hash"),
            rs.getString("previous_state"),
            rs.getString("new_state"),
        )
}
