package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.PlatformAgreementVersion
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Platform-level, not tenant-scoped — one agreement text shared by every
 * tenant, so no RLS policy and connections come from [Db.inTransaction]. */
@ApplicationScoped
class PlatformAgreementRepository {

    @Inject
    lateinit var db: Db

    /** The version a tenant onboarding right now must accept. */
    fun findCurrent(c: Connection): Optional<PlatformAgreementVersion> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM platform_agreement_version " +
                    "WHERE effective_from <= now() ORDER BY effective_from DESC LIMIT 1",
            ::map)

    fun findById(c: Connection, id: UUID?): Optional<PlatformAgreementVersion> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM platform_agreement_version " +
                    "WHERE platform_agreement_version_id = ?",
            ::map, id)

    /** content_hash is a generated column, so it is read back, never supplied. */
    fun insert(c: Connection, versionLabel: String?, content: String?,
               effectiveFrom: Instant?): PlatformAgreementVersion =
        db.queryOne(c,
            "INSERT INTO platform_agreement_version (version_label, content, effective_from) " +
                    "VALUES (?, ?, ?) RETURNING " + COLUMNS,
            ::map, versionLabel, content, effectiveFrom
        ).orElseThrow()

    private fun map(rs: ResultSet): PlatformAgreementVersion =
        PlatformAgreementVersion(
            rs.getObject("platform_agreement_version_id", UUID::class.java),
            rs.getString("version_label"),
            rs.getString("content"),
            rs.getString("content_hash"),
            rs.getTimestamp("effective_from").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "platform_agreement_version_id, version_label, content, content_hash, " +
                    "effective_from, created_at"
    }
}
