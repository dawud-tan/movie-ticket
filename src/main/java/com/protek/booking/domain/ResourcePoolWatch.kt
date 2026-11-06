package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/**
 * A registered FCM push subscription watching one seat map for availability changes.
 *
 * The full lifecycle is now implemented: registered while the seat-map page is open, deleted by
 * the client when it closes, pruned by the sweep if that delete never arrives, and removed when
 * FCM reports the token unregistered. `expiresAt` is the safety net for a crashed or
 * discarded tab, not the primary cleanup — which is why the window is short (20 minutes) rather
 * than open-ended.
 *
 * `resourcePoolSessionId` being null means a watch on the hall rather than one showing.
 * The lookup matches the pool/session pair exactly, so a null-session watch and a
 * specific-session watch are different subscriptions and do not substitute for each other.
 */
@JvmRecord
data class ResourcePoolWatch(
    val resourcePoolWatchId: UUID?,
    val tenantId: UUID?,
    val resourcePoolId: UUID?,
    val resourcePoolSessionId: UUID?,
    val customerId: UUID?,
    val fcmRegistrationToken: String?,
    val registeredAt: Instant?,
    val expiresAt: Instant?,
)
