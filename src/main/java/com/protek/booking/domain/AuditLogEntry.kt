package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/** Read-only view of an `audit_log` row, written exclusively by the
 * `reservation_audit` trigger — the application never inserts into this table
 * directly. */
@JvmRecord
data class AuditLogEntry(
    val auditId: Long,
    val tenantId: UUID?,
    val entityType: String?,
    val entityId: UUID?,
    val action: String?,
    val actorType: String?,
    val occurredAt: Instant?,
    val rowHash: String?,
    val previousStateJson: String?,
    val newStateJson: String?,
)
