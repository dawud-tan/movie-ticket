package com.protek.booking.db

import java.util.Optional
import java.util.UUID

/**
 * Supplies who is driving the current transaction — the `app_user_id` and the kind of
 * party — so [Db.inTenantTransaction] can publish them as the
 * `app.current_actor_id` and `app.current_actor_type` GUCs that the
 * `reservation_audit` trigger reads.
 *
 * Declared here, in the data-access package, and implemented in the security package —
 * dependency inversion on purpose. `Db` needs one fact from the security layer, and
 * inverting it this way keeps the arrow pointing from security toward db rather than
 * making the whole data-access layer depend on an authentication mechanism it otherwise
 * has nothing to do with.
 *
 * Empty is a legitimate, expected answer, not a failure: unauthenticated browsing, the
 * scheduled sweeps, and provisioning at startup all write reservation rows or run outside
 * any request, and those audit entries are correctly attributed to
 * `actor_type = 'system'` with no `actor_id`.
 */
interface ActorContext {

    fun currentActorId(): Optional<UUID>

    /**
     * What kind of party is acting, in `audit_log.actor_type`'s vocabulary —
     * `customer`, `tenant_staff` or `system`.
     *
     * Not an [Optional], unlike the id: there is always an answer, and for background
     * work it is `'system'` rather than "unknown". That distinction is the whole point of
     * the audit attribution — the trigger used to read `NEW.created_by_actor`, so a sweep completing a past
     * showing was logged as the customer who booked it.
     */
    fun currentActorType(): String
}
