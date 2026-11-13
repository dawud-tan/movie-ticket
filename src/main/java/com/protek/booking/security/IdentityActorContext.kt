package com.protek.booking.security

import com.protek.booking.db.ActorContext
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.util.Optional
import java.util.UUID

/**
 * The security-side implementation of [ActorContext]: supplies the authenticated
 * `app_user_id` so `audit_log.actor_id` is populated (the attributability
 * limb of the UU ITE Pasal 6 test).
 *
 * Same [Instance]-with-[ContextNotActiveException]-guard shape as
 * `CurrentTenant`, and for the same reason: the scheduled sweeps and provisioning
 * write reservation rows outside any request. Those genuinely have no human actor, and
 * `actor_type = 'system'` with a null `actor_id` is the correct record of that,
 * not a gap to paper over.
 */
@ApplicationScoped
class IdentityActorContext : ActorContext {

    @Inject
    lateinit var identity: Instance<SecurityIdentity>

    /** Only for [currentActorType], which must give the same answer as
     * `created_by_actor`. Injected rather than reimplemented so there is exactly one
     * place deciding what kind of party a login is — a row and its audit entry disagreeing
     * about that would be a discrepancy nobody could resolve afterwards. */
    @Inject
    lateinit var currentActor: CurrentActor

    override fun currentActorId(): Optional<UUID> {
        try {
            if (!identity.isResolvable) {
                return Optional.empty()
            }
            val current: SecurityIdentity? = identity.get()
            if (current == null || current.isAnonymous) {
                return Optional.empty()
            }
            val attribute: Any? = current.getAttribute(AppUserAugmentor.APP_USER_ID_ATTRIBUTE)
            return if (attribute is UUID) Optional.of(attribute) else Optional.empty()
        } catch (e: ContextNotActiveException) {
            return Optional.empty()
        }
    }

    /**
     * The audit half of the pair: which *kind* of party is acting, for
     * `audit_log.actor_type`.
     *
     * `CurrentActor.createdByActor()` already answers exactly this question for the
     * `created_by_actor` column, including the no-identity case (background work, which
     * is what `'system'` means), so this delegates rather than re-deriving it from roles.
     */
    override fun currentActorType(): String = currentActor.createdByActor()
}
