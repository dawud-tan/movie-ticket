package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.Optional
import java.util.UUID

/**
 * `tenant` carries no RLS policy — it's the control-plane table that implements
 * isolation for everything else — so every method here takes a plain
 * [Connection] obtained via [Db.inTransaction], never
 * [Db.inTenantTransaction].
 *
 * This is also why [findAll] is safe to have at all: cross-tenant reads are
 * possible here and nowhere else, which is exactly what platform-level work (billing
 * generation, migration loops over dedicated databases) needs and what tenant-scoped
 * request handling must never use.
 */
@ApplicationScoped
class TenantRepository {

    @Inject
    lateinit var db: Db

    fun findBySlug(c: Connection, slug: String?): Optional<Tenant> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM tenant WHERE slug = ?",
            ::map, slug)

    fun findById(c: Connection, tenantId: UUID?): Optional<Tenant> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM tenant WHERE tenant_id = ?",
            ::map, tenantId)

    /** Every tenant, for platform-level work only (billing generation, the
     * dedicated-database migration loop). Never reachable from a tenant-scoped request
     * path — a request handler that needs "the current tenant" resolves it from the
     * authenticated identity instead. */
    fun findAll(c: Connection): List<Tenant> =
        db.query(c, "SELECT " + COLUMNS + " FROM tenant ORDER BY created_at", ::map)

    fun findByStatus(c: Connection, status: String?): List<Tenant> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM tenant WHERE status = ? ORDER BY created_at",
            ::map, status)

    /**
     * Provisioning insert ("one scripted action from day one"). All three axes are
     * explicit parameters rather than defaulted, because a tenant created without deciding
     * them is a tenant whose isolation guarantees nobody chose — the DDL defaults exist
     * for the migration's benefit, not as an application-level shrug.
     */
    fun insert(c: Connection, slug: String?, displayName: String?, vertical: String?,
               computeMode: String?, dataMode: String?, domainMode: String?,
               customDomain: String?, dedicatedDbHost: String?, dedicatedDbName: String?,
               jurisdiction: String?, complaintPhone: String?): Tenant =
        db.queryOne(c,
            "INSERT INTO tenant (slug, display_name, vertical, compute_mode, data_mode, " +
                    "domain_mode, custom_domain, dedicated_db_host, dedicated_db_name, " +
                    "jurisdiction, complaint_phone) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, slug, displayName, vertical, computeMode, dataMode, domainMode,
            customDomain, dedicatedDbHost, dedicatedDbName, jurisdiction, complaintPhone
        ).orElseThrow()

    /** Non-payment enforcement is a status change on a column that already exists,
     * not new infrastructure. */
    fun updateStatus(c: Connection, tenantId: UUID?, status: String?): Int =
        db.execute(c, "UPDATE tenant SET status = ? WHERE tenant_id = ?", status, tenantId)

    fun updatePseRegistrationNo(c: Connection, tenantId: UUID?, pseRegistrationNo: String?): Int =
        db.execute(c, "UPDATE tenant SET pse_registration_no = ? WHERE tenant_id = ?",
            pseRegistrationNo, tenantId)

    /**
     * Records whether this tenant sells the same inventory through any channel this system
     * cannot see. The `other_sales_channels_consistent` CHECK rejects
     * a disclosed channel with no description, so passing `true` with a blank detail
     * fails loudly here rather than storing a disclosure that says nothing.
     */
    fun updateOtherSalesChannelDisclosure(c: Connection, tenantId: UUID?,
                                          disclosed: Boolean, detail: String?): Int =
        db.execute(c,
            "UPDATE tenant SET other_sales_channels_disclosed = ?, other_sales_channels_detail = ? " +
                    "WHERE tenant_id = ?",
            disclosed, if (disclosed) detail else null, tenantId)

    private fun map(rs: ResultSet): Tenant =
        Tenant(
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("slug"),
            rs.getString("display_name"),
            rs.getString("vertical"),
            rs.getString("compute_mode"),
            rs.getString("data_mode"),
            rs.getString("domain_mode"),
            rs.getString("custom_domain"),
            rs.getString("dedicated_db_host"),
            rs.getString("dedicated_db_name"),
            rs.getString("jurisdiction"),
            rs.getString("complaint_phone"),
            rs.getString("pse_registration_no"),
            rs.getString("status"),
            rs.getBoolean("other_sales_channels_disclosed"),
            rs.getString("other_sales_channels_detail"),
        )

    private companion object {
        const val COLUMNS =
            "tenant_id, slug, display_name, vertical, compute_mode, data_mode, domain_mode, " +
                    "custom_domain, dedicated_db_host, dedicated_db_name, jurisdiction, " +
                    "complaint_phone, pse_registration_no, status, " +
                    "other_sales_channels_disclosed, other_sales_channels_detail"
    }
}
