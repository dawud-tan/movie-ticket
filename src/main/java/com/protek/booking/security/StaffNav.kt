package com.protek.booking.security

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Named

/**
 * Whether to offer the `/admin/staff` link in the header.
 *
 * **Its own bean rather than one more method on [CurrentActor], and that is not a
 * style choice.** Adding a method to `CurrentActor` and calling it from a template
 * reproducibly renders every page 500: Qute's generated value resolver reaches
 * `CurrentActor_ClientProxy`, which takes its `bean == null` branch and runs the
 * method on the uninjected proxy itself, so `identity` is null and `current()`
 * throws a `NullPointerException`. The pre-existing methods on that class
 * (`isStaff`, `isPlatformAdmin`, …) are unaffected, the same new method works
 * perfectly when injected into Java, and a full `rm -rf target` does not change it —
 * measured, not guessed: swapping this one expression for `inject:actor.isPlatformAdmin`
 * in the same line of `base.html` turns 13 failures back into a green suite.
 *
 * So this class exists to keep the nav link off that path. It duplicates about six lines of
 * [CurrentActor]'s identity lookup, which is the cheaper of the two costs — the
 * alternative was leaving the header unable to say anything about staff management at all.
 *
 * Same rule as every other nav conditional: **this is UX, not the boundary.**
 * `StaffAdminResource` carries `@RolesAllowed(Roles.STAFF_MANAGER)` and is what
 * actually refuses anyone else, whether or not they can see the link.
 */
@ApplicationScoped
@Named("staffNav")
class StaffNav {

    @Inject
    lateinit var identity: Instance<SecurityIdentity>

    /** True when the signed-in caller holds [Roles.STAFF_MANAGER]. False for everyone
     * else, including background work with no request context — the same "no identity is a
     * normal state" rule [CurrentActor] follows. */
    fun visible(): Boolean {
        try {
            if (!identity.isResolvable) {
                return false
            }
            val current: SecurityIdentity? = identity.get()
            return current != null && !current.isAnonymous && current.hasRole(Roles.STAFF_MANAGER)
        } catch (e: ContextNotActiveException) {
            return false
        }
    }
}
