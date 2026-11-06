package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

@JvmRecord
data class ResourcePoolSession(
    val resourcePoolSessionId: UUID?,
    val tenantId: UUID?,
    val resourcePoolId: UUID?,
    val label: String?,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val salesCutoffAt: Instant?,
    val status: String?,
    /** The film being shown, or null for a session that is not a film — the schema is kept
     * vertical-neutral, and a `label`-only session is what the
     * hospitality mapping and every non-film event uses. */
    val filmId: UUID?,
)
