package com.protek.booking.security

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Named
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Read-only view of who is driving the current request, for the authorization decisions
 * `@RolesAllowed` cannot express.
 *
 * `@RolesAllowed` answers "may this *kind* of caller reach this endpoint."
 * It cannot answer "is this specific reservation group this specific customer's," which is
 * a real gap and not a theoretical one: RLS isolates tenants, so every customer of one
 * cinema can see every other customer's rows as far as the database is concerned. Both
 * layers are needed — the role check keeps strangers out of the endpoint, the ownership
 * check keeps one customer out of another's booking.
 *
 * Same [Instance] guard as `CurrentTenant` and `IdentityActorContext`:
 * background work has no request context and that is a normal state, not an error.
 */
@ApplicationScoped
@Named("actor")
class CurrentActor {

    @Inject
    lateinit var identity: Instance<SecurityIdentity>

    private fun current(): Optional<SecurityIdentity> {
        try {
            if (!identity.isResolvable) {
                return Optional.empty()
            }
            val resolved: SecurityIdentity? = identity.get()
            if (resolved == null || resolved.isAnonymous) {
                return Optional.empty()
            }
            return Optional.of(resolved)
        } catch (e: ContextNotActiveException) {
            return Optional.empty()
        }
    }

    fun isAuthenticated(): Boolean = current().isPresent

    fun appUserId(): Optional<UUID> = attribute(AppUserAugmentor.APP_USER_ID_ATTRIBUTE)

    /** The tenant-scoped `customer` row this login owns, if they have booked before.
     * Empty for staff, for platform admins, and for a customer's very first booking. */
    fun customerId(): Optional<UUID> = attribute(AppUserAugmentor.CUSTOMER_ID_ATTRIBUTE)

    fun email(): Optional<String> =
        current()
            .map { it.getAttribute<Any?>(AppUserAugmentor.EMAIL_ATTRIBUTE) }
            .filter { it is String }
            .map { it as String }

    /** The provider's `name` claim. Empty when it sent none. */
    fun fullName(): Optional<String> =
        current()
            .map { it.getAttribute<Any?>(AppUserAugmentor.NAME_ATTRIBUTE) }
            .filter { it is String }
            .map { it as String }

    /** The provider's `birthdate` claim, when there is one. Empty on this deployment —
     * see [AppUserAugmentor.BIRTHDATE_ATTRIBUTE] for why, and for why it is read anyway. */
    fun birthDate(): Optional<LocalDate> =
        current()
            .map { it.getAttribute<Any?>(AppUserAugmentor.BIRTHDATE_ATTRIBUTE) }
            .filter { it is LocalDate }
            .map { it as LocalDate }

    fun isStaff(): Boolean = current().map { it.hasRole(Roles.TENANT_STAFF) }.orElse(false)

    fun isPlatformAdmin(): Boolean = current().map { it.hasRole(Roles.PLATFORM_ADMIN) }.orElse(false)

    // NOTE: the staff-manager equivalent of these lives in StaffNav, NOT here. Adding a new
    // method to this class and calling it from a template breaks every page — see StaffNav's
    // javadoc for the measurement. Anything the header needs to ask about a new capability
    // belongs in its own bean until that is understood.

    fun isCustomer(): Boolean = current().map { it.hasRole(Roles.CUSTOMER) }.orElse(false)

    /** What to show in the header for a signed-in caller. Resolved here rather than in the
     * template so `base.html` needs no `Optional` handling — Qute expressions are
     * validated at build time and the simpler the expression, the less there is to get wrong
     * in a file the compiler does not check the way it checks Java. */
    fun displayName(): String = fullName().or { email() }.orElse("signed in")

    /**
     * The `created_by_actor` value for a reservation this caller creates — the column
     * that makes a counter sale distinguishable from a self-service booking
     * without special-casing, and which `audit_log.actor_type` mirrors.
     */
    fun createdByActor(): String {
        if (isStaff()) {
            return "tenant_staff"
        }
        if (isCustomer()) {
            return "customer"
        }
        // No identity: only reachable from background work, which is what 'system' means.
        return "system"
    }

    private fun attribute(name: String): Optional<UUID> =
        current()
            .map { it.getAttribute<Any?>(name) }
            .filter { it is UUID }
            .map { it as UUID }
}
