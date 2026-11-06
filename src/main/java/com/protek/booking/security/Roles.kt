package com.protek.booking.security

/**
 * The three roles, as constants so `@RolesAllowed` and the identity augmentor
 * can't drift apart on a typo — a misspelled role in `@RolesAllowed` fails open in
 * the sense that matters: it silently matches nobody, which looks like working
 * authorization right up until the day someone legitimate is locked out, or (worse) an
 * endpoint annotated with a role that no identity ever carries gets "fixed" by loosening it.
 *
 * The first three string values match `app_user.role`'s CHECK constraint exactly.
 * That is the contract: the database constrains which roles can exist, this class names
 * them, and the augmentor is the only thing that turns one into a `SecurityIdentity`
 * role. [STAFF_MANAGER] is the documented exception and says so at its own javadoc.
 *
 * An `object` of `const val`s, not a class with a companion: Java's `@RolesAllowed(Roles.CUSTOMER)`
 * needs a compile-time constant, and `const val` in an `object` is a `public static final` field
 * javac can fold.
 */
object Roles {

    /** An end customer of one tenant. Tenant-scoped: customer accounts are not a shared
     * platform-wide identity. */
    const val CUSTOMER = "customer"

    /** Cinema staff, scoped to one specific tenant, never global. Everything under
     * `/admin` and the counter-sale flow requires this. */
    const val TENANT_STAFF = "tenant_staff"

    /** The platform operator. The only global role — provisioning, billing generation, and
     * cross-tenant reads. */
    const val PLATFORM_ADMIN = "platform_admin"

    /**
     * **Not a value of `app_user.role`** — the one name here that is not, and the
     * reason the class javadoc hedges. It is derived by `AppUserAugmentor` from
     * `app_user.can_manage_staff` and granted *alongside*
     * [TENANT_STAFF], never instead of it.
     *
     * A capability rather than a fourth role because a manager *is* staff plus one
     * permission: they still sell at the counter and scan at the door. Modelling it as a
     * separate role would have meant adding it to every existing
     * `@RolesAllowed(TENANT_STAFF)` so managers kept access they already had — dozens
     * of edits where missing one locks a manager out of a screen silently, which is the
     * failure this class exists to prevent.
     *
     * Only `/admin/staff` requires it; everything else under `/admin` stays on
     * [TENANT_STAFF], unchanged.
     */
    const val STAFF_MANAGER = "staff_manager"
}
