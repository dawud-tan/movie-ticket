package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.Optional
import java.util.UUID

/**
 * Identity lookup for the authorization model. Like [TenantRepository], every
 * method takes a connection from [Db.inTransaction] rather than
 * [Db.inTenantTransaction] — `app_user` carries no RLS policy because it is
 * the table consulted to discover which tenant a request belongs to, and a table cannot be
 * protected by the mechanism it bootstraps (V1 spells this out at the `app_user` DDL).
 *
 * The compensating rule, which every method here obeys: reads are keyed on the OIDC
 * `sub` or the verified email out of a signature-validated token, never on a
 * tenant id supplied by the caller. There is deliberately no
 * `findByTenantAndEmail`-shaped method, because that would be the first place a
 * caller-supplied tenant id could sneak into an identity decision.
 */
@ApplicationScoped
class AppUserRepository {

    @Inject
    lateinit var db: Db

    /** Preferred lookup once a user has signed in at least once: Google's `sub` is
     * stable, where an email address can be reassigned to a different person. */
    fun findBySubject(c: Connection, subject: String?): Optional<AppUser> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM app_user WHERE subject = ?",
            ::map, subject)

    /** First-login lookup, and the only identifier available when provisioning staff who
     * have never signed in. Case-insensitive by the column's `case_insensitive` collation,
     * so a plain `=` matches `Foo@x` to `foo@x` and still uses the UNIQUE index, which
     * `lower(email) = lower(?)` could not: that was a sequential scan of every login. */
    fun findByEmail(c: Connection, email: String?): Optional<AppUser> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM app_user WHERE email = ?",
            ::map, email)

    fun findById(c: Connection, appUserId: UUID?): Optional<AppUser> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM app_user WHERE app_user_id = ?",
            ::map, appUserId)

    fun findByTenant(c: Connection, tenantId: UUID?): List<AppUser> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM app_user WHERE tenant_id = ? ORDER BY created_at",
            ::map, tenantId)

    /**
     * The staff list behind `/admin/staff`, inactive rows included — a
     * deactivated colleague has to stay visible or nobody can reactivate them.
     *
     * **The `tenant_id` argument is the whole of this query's tenant isolation.**
     * Every other admin listing gets that from RLS; `app_user` carries no policy, for
     * the reason the app_user DDL gives, so the caller must pass `CurrentTenant.id()` and never
     * anything a request supplied. That is a narrower contract than it looks: this method
     * takes a tenant, unlike the deliberately-absent `findByTenantAndEmail`, because it
     * enumerates one tenant's own staff rather than resolving an identity.
     */
    fun findStaffByTenant(c: Connection, tenantId: UUID?): List<AppUser> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM app_user " +
                    "WHERE tenant_id = ? AND role = '" + AppUser.ROLE_TENANT_STAFF + "' " +
                    "ORDER BY created_at",
            ::map, tenantId)

    fun insert(c: Connection, email: String?, role: String?, tenantId: UUID?, customerId: UUID?): AppUser =
        db.queryOne(c,
            "INSERT INTO app_user (email, role, tenant_id, customer_id) VALUES (?, ?, ?, ?) " +
                    "RETURNING " + COLUMNS,
            ::map, email, role, tenantId, customerId
        ).orElseThrow()

    /**
     * Provisions a staff identity by email, before they have ever signed in.
     *
     * **Must run inside [Db.inTenantTransaction].** The
     * `app_user_privilege_audit_insert` trigger fires on this row and writes to
     * `audit_log`, which is under FORCE ROW LEVEL SECURITY — without the tenant GUC
     * that insert is refused by the policy and takes this statement with it. The plain
     * [insert] above has no such requirement because the augmentor uses it for
     * `customer` rows, which the trigger deliberately ignores.
     */
    fun insertStaff(c: Connection, email: String?, tenantId: UUID?, canManageStaff: Boolean): AppUser =
        db.queryOne(c,
            "INSERT INTO app_user (email, role, tenant_id, can_manage_staff) " +
                    "VALUES (?, '" + AppUser.ROLE_TENANT_STAFF + "', ?, ?) RETURNING " + COLUMNS,
            ::map, email, tenantId, canManageStaff
        ).orElseThrow()

    /**
     * Grants or withdraws the staff-management capability, scoped to one tenant.
     *
     * The `tenant_id` and `role` predicates are not belt-and-braces: with no
     * RLS on this table they are the only thing standing between a manager of one cinema and
     * a row belonging to another, and the CHECK constraint would reject a non-staff grant
     * with a 500 rather than the zero-rows-updated this produces. Audited by the privilege-audit trigger,
     * so it must run inside [Db.inTenantTransaction].
     */
    fun setManageStaffWithinTenant(c: Connection, appUserId: UUID?, tenantId: UUID?, canManage: Boolean): Int =
        db.execute(c,
            "UPDATE app_user SET can_manage_staff = ? " +
                    "WHERE app_user_id = ? AND tenant_id = ? AND role = '" +
                    AppUser.ROLE_TENANT_STAFF + "'",
            canManage, appUserId, tenantId)

    /** Deactivates or restores one of this tenant's staff. Same scoping contract and same
     * [Db.inTenantTransaction] requirement as [setManageStaffWithinTenant]. */
    fun setActiveWithinTenant(c: Connection, appUserId: UUID?, tenantId: UUID?, active: Boolean): Int =
        db.execute(c,
            "UPDATE app_user SET is_active = ? " +
                    "WHERE app_user_id = ? AND tenant_id = ? AND role = '" +
                    AppUser.ROLE_TENANT_STAFF + "'",
            active, appUserId, tenantId)

    /**
     * This tenant's active managers, locked — the first statement of the lockout guard in
     * `StaffAdminService`, which refuses to remove the last one.
     *
     * **Locked, not counted.** A `count(*)` let two managers demote each other at once: under
     * READ COMMITTED each counted two, and both committed, leaving none. The audit trigger's
     * advisory lock did not stop it, because it is taken by the UPDATE, after the count.
     * `FOR UPDATE` makes the second guard wait on the first's rows, then re-read them as
     * committed and see one. In `app_user_id` order, so two guards never lock in opposite order.
     */
    fun lockActiveManagers(c: Connection, tenantId: UUID?): List<UUID> =
        db.query(c,
            "SELECT app_user_id FROM app_user " +
                    "WHERE tenant_id = ? AND role = '" + AppUser.ROLE_TENANT_STAFF + "' " +
                    "AND is_active AND can_manage_staff ORDER BY app_user_id FOR UPDATE",
            { rs -> rs.getObject("app_user_id", UUID::class.java) }, tenantId)

    /**
     * Binds the OIDC subject on first login and stamps `last_login_at`.
     *
     * The `subject IS NULL` guard is the point of the statement: without it, a
     * second identity provider (or a Google account whose email was reassigned) could
     * re-point an existing, already-bound user row at a different subject, silently
     * handing one person another's tenant and role. Binding is one-way — rebinding is an
     * administrative action, not something a login does.
     */
    fun bindSubjectOnFirstLogin(c: Connection, appUserId: UUID?, subject: String?): Int =
        db.execute(c,
            "UPDATE app_user SET subject = ?, last_login_at = now() " +
                    "WHERE app_user_id = ? AND subject IS NULL",
            subject, appUserId)

    fun touchLastLogin(c: Connection, appUserId: UUID?): Int =
        db.execute(c, "UPDATE app_user SET last_login_at = now() WHERE app_user_id = ?",
            appUserId)

    /** Links a `customer`-role login to the tenant-scoped `customer` row that
     * owns their reservations — set the first time they book, not at provisioning, since a
     * customer row only exists once there's something to attach it to. */
    fun linkCustomer(c: Connection, appUserId: UUID?, customerId: UUID?): Int =
        db.execute(c,
            "UPDATE app_user SET customer_id = ? WHERE app_user_id = ? AND customer_id IS NULL",
            customerId, appUserId)

    fun setActive(c: Connection, appUserId: UUID?, active: Boolean): Int =
        db.execute(c, "UPDATE app_user SET is_active = ? WHERE app_user_id = ?",
            active, appUserId)

    private fun map(rs: ResultSet): AppUser {
        val lastLogin = rs.getTimestamp("last_login_at")
        return AppUser(
            rs.getObject("app_user_id", UUID::class.java),
            rs.getString("subject"),
            rs.getString("email"),
            rs.getString("role"),
            rs.getBoolean("can_manage_staff"),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("customer_id", UUID::class.java),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
            lastLogin?.toInstant(),
        )
    }

    private companion object {
        const val COLUMNS =
            "app_user_id, subject, email, role, can_manage_staff, tenant_id, customer_id, " +
                    "is_active, created_at, last_login_at"
    }
}
