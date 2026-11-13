package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.PolicyDocument
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Booking-time policy snapshot. [findLatest] is what `reservation`
 * freezes a reference to at hold time, so a tenant editing its cancellation policy later
 * can never retroactively change what an earlier customer agreed to.
 */
@ApplicationScoped
class PolicyDocumentRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, docType: String?, version: Int,
               content: String?, effectiveFrom: Instant?): PolicyDocument =
        db.queryOne(c,
            "INSERT INTO policy_document (tenant_id, doc_type, version, content, effective_from) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, docType, version, content, effectiveFrom
        ).orElseThrow()

    fun findLatest(c: Connection, tenantId: UUID?, docType: String?): Optional<PolicyDocument> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM policy_document " +
                    "WHERE tenant_id = ? AND doc_type = ? AND effective_from <= now() " +
                    "ORDER BY version DESC LIMIT 1",
            ::map, tenantId, docType)

    fun findById(c: Connection, tenantId: UUID?, policyDocumentId: UUID?): Optional<PolicyDocument> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM policy_document WHERE tenant_id = ? AND policy_document_id = ?",
            ::map, tenantId, policyDocumentId)

    private fun map(rs: ResultSet): PolicyDocument =
        PolicyDocument(
            rs.getObject("policy_document_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("doc_type"),
            rs.getInt("version"),
            rs.getString("content"),
            rs.getTimestamp("effective_from").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "policy_document_id, tenant_id, doc_type, version, content, effective_from"
    }
}
