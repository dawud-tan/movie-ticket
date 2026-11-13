package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

@JvmRecord
data class ResourcePool(
    val resourcePoolId: UUID?,
    val tenantId: UUID?,
    val name: String?,
    val address: String?,
    val timezone: String?,
    val blockClaimMode: String?,
    /** The branch this hall sits in, or null for a hall not yet assigned to one —
     * every pool created before branches existed has no branch and is still a valid hall. */
    val siteId: UUID?,
    val createdAt: Instant?,
)
