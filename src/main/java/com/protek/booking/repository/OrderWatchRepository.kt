package com.protek.booking.repository

import com.protek.booking.db.Db
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.util.UUID

/** `order_watch` (`V1__init_schema.sql`): the buyer's opt-in push for one order. */
@ApplicationScoped
class OrderWatchRepository {

    @Inject
    lateinit var db: Db

    /** One device's watch on one order; tokens and the language each was registered in. */
    data class Watcher(val token: String, val language: String)

    /** An upsert, so a second tap or a resend is the same row, now in the language of the last one. */
    fun register(c: Connection, tenantId: UUID, groupId: UUID, token: String, language: String) =
        db.execute(c,
            "INSERT INTO order_watch (tenant_id, reservation_group_id, fcm_registration_token, language) " +
                    "VALUES (?, ?, ?, ?) ON CONFLICT (reservation_group_id, fcm_registration_token) " +
                    "DO UPDATE SET language = excluded.language, registered_at = now()",
            tenantId, groupId, token, language)

    fun delete(c: Connection, tenantId: UUID, groupId: UUID, token: String): Int =
        db.execute(c,
            "DELETE FROM order_watch WHERE tenant_id = ? AND reservation_group_id = ? AND fcm_registration_token = ?",
            tenantId, groupId, token)

    fun findByGroup(c: Connection, tenantId: UUID, groupId: UUID): List<Watcher> =
        db.query(c,
            "SELECT fcm_registration_token, language FROM order_watch " +
                    "WHERE tenant_id = ? AND reservation_group_id = ? ORDER BY registered_at",
            { rs -> Watcher(rs.getString("fcm_registration_token"), rs.getString("language")) },
            tenantId, groupId)

    /** FCM said the token is gone: every order it watched is equally dead. */
    fun deleteByToken(c: Connection, tenantId: UUID, token: String): Int =
        db.execute(c, "DELETE FROM order_watch WHERE tenant_id = ? AND fcm_registration_token = ?",
            tenantId, token)
}
