package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.TenantAgreementAcceptance
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.Optional
import java.util.UUID

/** Tenant-scoped and under RLS, unlike [PlatformAgreementRepository] — the
 * acceptance is a fact about one tenant, so it belongs behind the same isolation as that
 * tenant's other rows. */
@ApplicationScoped
class TenantAgreementAcceptanceRepository {

    @Inject
    lateinit var db: Db

    /** `ipAddress` is cast in SQL rather than bound as a string: the column is
     * `inet`, and pgJDBC has no native mapping for it, so an explicit `?::inet`
     * on the way in and `::text` on the way out keeps the column's own validation
     * (which rejects a malformed address) rather than degrading it to free text. */
    fun insert(c: Connection, tenantId: UUID?, versionId: UUID?,
               acceptedByName: String?, acceptedByEmail: String?,
               ipAddress: String?): TenantAgreementAcceptance =
        db.queryOne(c,
            "INSERT INTO tenant_agreement_acceptance " +
                    "(tenant_id, platform_agreement_version_id, accepted_by_name, accepted_by_email, ip_address) " +
                    "VALUES (?, ?, ?, ?, ?::inet) RETURNING " + COLUMNS,
            ::map, tenantId, versionId, acceptedByName, acceptedByEmail, ipAddress
        ).orElseThrow()

    fun findByTenant(c: Connection, tenantId: UUID?): List<TenantAgreementAcceptance> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM tenant_agreement_acceptance " +
                    "WHERE tenant_id = ? ORDER BY accepted_at",
            ::map, tenantId)

    fun find(c: Connection, tenantId: UUID?, versionId: UUID?): Optional<TenantAgreementAcceptance> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM tenant_agreement_acceptance " +
                    "WHERE tenant_id = ? AND platform_agreement_version_id = ?",
            ::map, tenantId, versionId)

    private fun map(rs: ResultSet): TenantAgreementAcceptance =
        TenantAgreementAcceptance(
            rs.getObject("tenant_agreement_acceptance_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("platform_agreement_version_id", UUID::class.java),
            rs.getTimestamp("accepted_at").toInstant(),
            rs.getString("accepted_by_name"),
            rs.getString("accepted_by_email"),
            rs.getString("ip_address"),
        )

    private companion object {
        const val COLUMNS =
            "tenant_agreement_acceptance_id, tenant_id, platform_agreement_version_id, " +
                    "accepted_at, accepted_by_name, accepted_by_email, ip_address::text AS ip_address"
    }
}
