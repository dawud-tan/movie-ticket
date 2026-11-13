package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.javaTrim
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.security.CurrentActor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.sql.Connection
import java.util.Locale
import java.util.UUID

/**
 * Adding, removing and promoting a tenant's own staff — the second staff member
 * onward, which until now meant a hand-written INSERT in psql.
 *
 * **This is the one service in the tree whose tenant isolation is entirely its own
 * doing.** Everywhere else `db.inTenantTransaction` plus an RLS policy means a
 * forgotten predicate returns nothing; here `app_user` carries no policy at all (the app_user
 * DDL explains why — it is the table consulted to discover the tenant, and a table cannot be
 * protected by the mechanism it bootstraps), so a forgotten `tenant_id` predicate
 * reaches another cinema's rows. Every repository call below therefore passes
 * `currentTenant.id()` explicitly, and the repository methods take it as an argument
 * rather than deriving it, so the omission would be visible at the call site.
 *
 * The transactions are still `inTenantTransaction` despite RLS not applying, for a
 * second reason: the privilege-audit trigger writes to `audit_log`, which *is* under
 * FORCE ROW LEVEL SECURITY, and needs `app.current_tenant_id` and
 * `app.current_actor_id` set to record who granted what.
 */
@ApplicationScoped
class StaffAdminService {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var appUserRepo: AppUserRepository
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var currentActor: CurrentActor

    /** Thrown for the things a manager can get wrong at the form; the page renders the
     * message back rather than 500ing, the same shape `HolidayAdminResource` uses. */
    // Open, as the Java's public static class was not final.
    open class StaffAdminException(message: String?) : RuntimeException(message)

    fun listStaff(): List<AppUser> {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c -> appUserRepo.findStaffByTenant(c, tenantId) }
    }

    /**
     * Adds a staff identity by email address.
     *
     * **The refusal wording is a security decision, not copy.** `app_user.email`
     * is UNIQUE across the whole platform while tenancy is per row, so an address already in
     * use somewhere cannot be inserted here and must not be updated into this tenant either —
     * that would migrate a person's identity, and with it the `customer_id` linking
     * their order history, from whichever tenant they belong to into this one.
     *
     * So the conflict has to be reported, and what it may say depends on where the row
     * lives. A row in *this* tenant can be described precisely: it is the manager's own
     * staff list or their own customer. A row in *another* tenant gets a message that
     * says only that the address is in use somewhere on the platform, never which venue —
     * cross-tenant association is the part worth protecting.
     *
     * **Residual disclosure, stated rather than hidden:** a manager can still learn that
     * an address has *some* account on this platform, because a refusal has to explain
     * itself. That is unavoidable while the column is globally UNIQUE; what is avoidable, and
     * avoided, is revealing where.
     */
    fun invite(rawEmail: String?): AppUser {
        val email = normalise(rawEmail)
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val existing = appUserRepo.findByEmail(c, email)
            if (existing.isPresent) {
                throw StaffAdminException(conflictMessage(existing.get(), tenantId))
            }
            val created = appUserRepo.insertStaff(c, email, tenantId, false)
            LOG.infof("Staff %s added to tenant %s by %s", email, tenantId,
                currentActor.email().orElse("unknown"))
            created
        }
    }

    /**
     * Grants or withdraws the staff-management capability.
     *
     * Withdrawal is guarded against emptying the tenant: a manager removing the last one —
     * most likely themselves, since that is the row in front of them — would leave the cinema
     * with a screen nobody can open and no remedy short of the psql UPDATE this whole feature
     * exists to replace. The same guard covers [setActive] for the same reason.
     */
    fun setManager(appUserId: UUID?, canManage: Boolean) {
        val tenantId = currentTenant.id()
        db.inTenantTransaction(tenantId) { c ->
            if (!canManage) {
                requireAnotherManagerRemains(c, appUserId, tenantId)
            }
            val updated = appUserRepo.setManageStaffWithinTenant(c, appUserId, tenantId, canManage)
            if (updated == 0) {
                // Zero rows means the id names nobody on this tenant's staff — a stale page,
                // or a hand-edited form. Same answer either way, and deliberately the same
                // answer it would give for a row belonging to another tenant.
                throw StaffAdminException("That staff member is not on your list.")
            }
            updated
        }
    }

    /** Deactivates or restores a staff member. Never deletes: `audit_log` entries point
     * at this row, and `AppUserAugmentor` already treats an inactive identity as one
     * that gets no roles at all, which is the enforcement. */
    fun setActive(appUserId: UUID?, active: Boolean) {
        val tenantId = currentTenant.id()
        db.inTenantTransaction(tenantId) { c ->
            if (!active) {
                requireAnotherManagerRemains(c, appUserId, tenantId)
            }
            val updated = appUserRepo.setActiveWithinTenant(c, appUserId, tenantId, active)
            if (updated == 0) {
                throw StaffAdminException("That staff member is not on your list.")
            }
            updated
        }
    }

    /**
     * Refuses the change if `appUserId` is this tenant's last active manager.
     *
     * Counts rather than compares against the acting identity, because the lockout does not
     * care who is doing it: one manager demoting another is as final as demoting themselves
     * when there are only two of them and one is already inactive.
     *
     * The managers are locked before the target is looked at, and the target is looked for
     * among them: a target read beforehand could be a manager another guard has just demoted.
     */
    private fun requireAnotherManagerRemains(c: Connection, appUserId: UUID?, tenantId: UUID) {
        val managers = appUserRepo.lockActiveManagers(c, tenantId)
        if (appUserId !in managers) {
            // Not currently an active manager, so removing them cannot empty the tenant.
            return
        }
        if (managers.size <= 1) {
            throw StaffAdminException(
                "This is your cinema's only staff manager. Give someone else the manager " +
                        "role first, or nobody will be able to open this page.")
        }
    }

    /**
     * Lower-cased and trimmed. The column's `case_insensitive` collation now refuses a
     * second, case-varying row for the same person by itself; lower-casing is kept so the
     * staff list shows one spelling of every address, whatever the manager typed.
     */
    // javaTrim, not Kotlin's trim; lowercase(Locale.ROOT) is the same call as
    // toLowerCase(Locale.ROOT).
    private fun normalise(rawEmail: String?): String {
        val email = rawEmail?.javaTrim()?.lowercase(Locale.ROOT) ?: ""
        if (email.isEmpty()) {
            throw StaffAdminException("Enter an email address.")
        }
        // Deliberately shallow: the address has to be a real Google account to ever sign in,
        // which no pattern here can establish, so this rejects only what is obviously not an
        // address rather than pretending to validate deliverability.
        if (email.indexOf('@') <= 0 || email.indexOf('@') != email.lastIndexOf('@')
            || email.endsWith("@") || email.contains(" ")) {
            throw StaffAdminException("That does not look like an email address.")
        }
        return email
    }

    private fun conflictMessage(existing: AppUser, tenantId: UUID): String {
        if (tenantId != existing.tenantId) {
            // Another tenant's row, or a platform admin. Says that it is taken, never where.
            return "That address is already in use on this platform, so it cannot be added " +
                    "here. Staff need an address that is not already registered."
        }
        if (AppUser.ROLE_TENANT_STAFF == existing.role) {
            return if (existing.active)
                "That address is already on your staff list."
            else
                "That address is on your staff list but deactivated — reactivate it below."
        }
        // Their own customer. Promoting in place is what the app_user_customer_only_for_customer_role
        // CHECK forbids, and for a good reason worth passing on rather than paraphrasing as an
        // error code: customer_id would have to be cleared, and BookingService.listBookingHistory
        // returns nothing without it, so their past orders would silently vanish from /orders.
        return "That address is a customer account at your cinema. Promoting it would detach " +
                "their existing orders, so staff access needs a separate address."
    }

    private companion object {
        private val LOG: Logger = Logger.getLogger(StaffAdminService::class.java)
    }
}
