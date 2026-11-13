package com.protek.booking.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A branch — one cinema, containing several halls.
 *
 * The level `resource_pool` was missing: a pool is a single auditorium, so without
 * this a chain is a flat list of a hundred-odd rows mostly named "Studio 1", and "what is
 * playing at this branch" is not a question the schema can answer.
 */
@JvmRecord
data class Site(
    val siteId: UUID?,
    val tenantId: UUID?,
    val name: String?,
    /** The path segment this branch is published under, unique per tenant. Derived
     * from `name`, but stored rather than derived on read: a link that has been
     * pasted into a WhatsApp group must not move because someone tidied the name. */
    val slug: String?,
    val city: String?,
    val province: String?,
    val address: String?,
    /** The branch's own timezone. A chain spanning Kalimantan and Java is not one
     * timezone; `resource_pool.timezone` remains a per-hall override. */
    val timezone: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val isActive: Boolean,
    val createdAt: Instant?,
)
