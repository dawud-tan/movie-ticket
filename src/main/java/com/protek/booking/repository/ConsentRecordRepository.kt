package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.ConsentRecord
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * `consent_record`, whose first writer is the age attestation.
 *
 * Insert only, and that is the whole intended surface. Nothing in the application reads these
 * rows back to make a decision, on purpose: an attestation against one classification says
 * nothing about another, so a read that skipped the next prompt would be the defect the age attestation exists
 * to fix. [findByCustomer] is the evidentiary read — what a person asks the database when
 * they need to know what somebody attested to — and is exercised by the tests rather than by any
 * screen, because there is no screen that should be deciding anything from it.
 */
@ApplicationScoped
class ConsentRecordRepository {

    @Inject
    lateinit var db: Db

    /**
     * Records one assertion, stamped `now()` inside the caller's transaction.
     *
     * Takes a [Connection] like everything else here, which is what lets the hold write the
     * attestation in the same transaction as the hold it authorises: a committed booking of a
     * restricted film with no record of the attestation that permitted it would be the worst of
     * the available outcomes.
     *
     * `policyTextHash` is bound exactly as the caller computed it — no trimming, casing or
     * re-encoding here — because the stored hash is only evidence while it matches the
     * sentence that was hashed.
     */
    fun insert(c: Connection, tenantId: UUID?, customerId: UUID?, purpose: String?,
               legalBasis: String?, policyTextHash: String?): ConsentRecord =
        db.queryOne(c,
            "INSERT INTO consent_record (tenant_id, customer_id, purpose, legal_basis, " +
                    "consent_given_at, policy_text_hash) VALUES (?, ?, ?, ?, now(), ?) " +
                    "RETURNING " + COLUMNS,
            ::map, tenantId, customerId, purpose, legalBasis, policyTextHash
        ).orElseThrow()

    /** Everything this customer has asserted, newest first. */
    fun findByCustomer(c: Connection, tenantId: UUID?, customerId: UUID?): List<ConsentRecord> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM consent_record WHERE tenant_id = ? AND customer_id = ? " +
                    "ORDER BY created_at DESC, consent_id DESC",
            ::map, tenantId, customerId)

    private fun map(rs: ResultSet): ConsentRecord =
        ConsentRecord(
            rs.getObject("consent_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("customer_id", UUID::class.java),
            rs.getString("purpose"),
            rs.getString("legal_basis"),
            rs.getTimestamp("consent_given_at")?.toInstant(),
            rs.getTimestamp("consent_withdrawn_at")?.toInstant(),
            rs.getString("policy_text_hash"),
            rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "consent_id, tenant_id, customer_id, purpose, legal_basis, consent_given_at, " +
                    "consent_withdrawn_at, policy_text_hash, created_at"
    }
}
