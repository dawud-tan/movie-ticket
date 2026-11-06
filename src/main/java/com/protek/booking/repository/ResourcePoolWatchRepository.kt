package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePoolWatch
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

@ApplicationScoped
class ResourcePoolWatchRepository {

    @Inject
    lateinit var db: Db

    // idx_resource_pool_watch_dedupe is NULLS NOT DISTINCT, so a pool-wide watch (no session)
    // conflicts with its own re-registration like any other. Re-registering the same token for
    // the same pool/session refreshes the expiry window rather than erroring.
    fun register(c: Connection, tenantId: UUID?, resourcePoolId: UUID?,
                 resourcePoolSessionId: UUID?, customerId: UUID?, fcmToken: String?): ResourcePoolWatch =
        db.queryOne(c,
            "INSERT INTO resource_pool_watch " +
                    "(tenant_id, resource_pool_id, resource_pool_session_id, customer_id, fcm_registration_token) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (resource_pool_id, resource_pool_session_id, fcm_registration_token) " +
                    "DO UPDATE SET tenant_id = excluded.tenant_id, customer_id = excluded.customer_id, " +
                    "registered_at = now(), expires_at = now() + interval '20 minutes' " +
                    "RETURNING " + COLUMNS,
            ::map, tenantId, resourcePoolId, resourcePoolSessionId, customerId, fcmToken
        ).orElseThrow()

    fun findByPool(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): List<ResourcePoolWatch> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM resource_pool_watch " +
                    "WHERE tenant_id = ? AND resource_pool_id = ? AND expires_at > now() " +
                    "ORDER BY registered_at",
            ::map, tenantId, resourcePoolId)

    /**
     * The watcher lookup: distinct tokens for one
     * pool and one session, excluding expired rows.
     *
     * Three details are load-bearing rather than stylistic:
     *
     * **The COALESCE on both sides** matches a NULL `resource_pool_session_id` — a
     * watch on the hall as a whole — against a NULL session argument, which plain `=` never
     * would since `NULL = NULL` is NULL. It treats two NULLs as equal, as the
     * `idx_resource_pool_watch_dedupe` index's NULLS NOT DISTINCT does, so registration and
     * lookup agree on what "the same watch" means. The zero UUID is only this statement's
     * stand-in for NULL; the index has none. It keeps the session column out of
     * `idx_resource_pool_watch_lookup`'s condition, which is fine for 20 minutes of watches.
     *
     * **DISTINCT** because one device can legitimately hold a watch on the hall and on a
     * specific session at once, and FCM would otherwise be asked to deliver the same notification
     * to it twice.
     *
     * **`expires_at > now()`** so a tab that was closed without unregistering — a
     * crash, a killed browser — stops receiving pushes within the 20-minute window even before
     * the pruning sweep removes the row.
     */
    fun findTokensToNotify(c: Connection, tenantId: UUID?, resourcePoolId: UUID?,
                           resourcePoolSessionId: UUID?): List<String> =
        db.query(c,
            "SELECT DISTINCT fcm_registration_token FROM resource_pool_watch " +
                    "WHERE tenant_id = ? AND resource_pool_id = ? " +
                    "AND COALESCE(resource_pool_session_id, '00000000-0000-0000-0000-000000000000'::uuid) " +
                    "  = COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid) " +
                    "AND expires_at > now()",
            { rs -> rs.getString("fcm_registration_token") },
            tenantId, resourcePoolId, resourcePoolSessionId)

    /**
     * Removes every watch held by one token, across pools and sessions.
     *
     * Called when FCM reports a token as unregistered or invalid (the first failure path):
     * the subscription is gone, so retrying it forever is pure waste, and the row is what keeps
     * it in the send set. Scoped by token rather than by row id because a dead device may hold
     * several watches and all of them are equally dead.
     */
    fun deleteByToken(c: Connection, tenantId: UUID?, fcmToken: String?): Int =
        db.execute(c,
            "DELETE FROM resource_pool_watch WHERE tenant_id = ? AND fcm_registration_token = ?",
            tenantId, fcmToken)

    /**
     * The client's own unregister, when the seat-map page closes — the primary cleanup path,
     * of which `expires_at` is only the safety net.
     *
     * Matches the dedupe index's shape so it removes exactly the watch [register]
     * created, rather than every watch this device holds.
     */
    fun deleteWatch(c: Connection, tenantId: UUID?, resourcePoolId: UUID?,
                    resourcePoolSessionId: UUID?, fcmToken: String?): Int =
        db.execute(c,
            "DELETE FROM resource_pool_watch WHERE tenant_id = ? AND resource_pool_id = ? " +
                    "AND COALESCE(resource_pool_session_id, '00000000-0000-0000-0000-000000000000'::uuid) " +
                    "  = COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid) " +
                    "AND fcm_registration_token = ?",
            tenantId, resourcePoolId, resourcePoolSessionId, fcmToken)

    /** Prunes rows whose window has passed — the crash-and-dropped-connection safety net,
     * not the primary cleanup. Keeps the send set and the lookup index small rather
     * than accumulating one row per abandoned tab forever. */
    fun deleteExpired(c: Connection, tenantId: UUID?): Int =
        db.execute(c,
            "DELETE FROM resource_pool_watch WHERE tenant_id = ? AND expires_at <= now()",
            tenantId)

    private fun map(rs: ResultSet): ResourcePoolWatch =
        ResourcePoolWatch(
            rs.getObject("resource_pool_watch_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("resource_pool_id", UUID::class.java),
            rs.getObject("resource_pool_session_id", UUID::class.java),
            rs.getObject("customer_id", UUID::class.java),
            rs.getString("fcm_registration_token"),
            rs.getTimestamp("registered_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "resource_pool_watch_id, tenant_id, resource_pool_id, resource_pool_session_id, " +
                    "customer_id, fcm_registration_token, registered_at, expires_at"
    }
}
