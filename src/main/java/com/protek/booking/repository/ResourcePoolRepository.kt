package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePool
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.Optional
import java.util.UUID

@ApplicationScoped
class ResourcePoolRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, name: String?, address: String?,
               timezone: String?, blockClaimMode: String?): ResourcePool =
        insert(c, tenantId, name, address, timezone, blockClaimMode, null)

    /** As above, with the branch this hall belongs to. `siteId` may be null —
     * a hall not yet assigned to a branch is still a hall. */
    fun insert(c: Connection, tenantId: UUID?, name: String?, address: String?,
               timezone: String?, blockClaimMode: String?, siteId: UUID?): ResourcePool =
        db.queryOne(c,
            "INSERT INTO resource_pool (tenant_id, name, address, timezone, block_claim_mode, site_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, name, address, timezone, blockClaimMode, siteId
        ).orElseThrow()

    fun findBySite(c: Connection, tenantId: UUID?, siteId: UUID?): List<ResourcePool> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM resource_pool WHERE tenant_id = ? AND site_id = ? ORDER BY name",
            ::map, tenantId, siteId)

    /** Halls with no branch yet — surfaced in admin rather than hidden, so a hall cannot
     * quietly vanish from the catalogue by being unassigned. */
    fun findWithoutSite(c: Connection, tenantId: UUID?): List<ResourcePool> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM resource_pool WHERE tenant_id = ? AND site_id IS NULL ORDER BY name",
            ::map, tenantId)

    fun assignToSite(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, siteId: UUID?): Int =
        db.execute(c,
            "UPDATE resource_pool SET site_id = ? WHERE tenant_id = ? AND resource_pool_id = ?",
            siteId, tenantId, resourcePoolId)

    fun findById(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): Optional<ResourcePool> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM resource_pool WHERE tenant_id = ? AND resource_pool_id = ?",
            ::map, tenantId, resourcePoolId)

    fun findAllByTenant(c: Connection, tenantId: UUID?): List<ResourcePool> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM resource_pool WHERE tenant_id = ? ORDER BY name",
            ::map, tenantId)

    private fun map(rs: ResultSet): ResourcePool =
        ResourcePool(
            rs.getObject("resource_pool_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("timezone"),
            rs.getString("block_claim_mode"),
            rs.getObject("site_id", UUID::class.java),
            rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "resource_pool_id, tenant_id, name, address, timezone, block_claim_mode, site_id, created_at"
    }
}
