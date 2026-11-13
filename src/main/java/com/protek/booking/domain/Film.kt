package com.protek.booking.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A film.
 *
 * Before this, a showing's title was free text on `resource_pool_session.label`, so
 * the same film in three studios was three unrelated strings — no film-first browse, no
 * "where is this playing", and nothing to group reporting by.
 */
@JvmRecord
data class Film(
    val filmId: UUID?,
    val tenantId: UUID?,
    val title: String?,
    /** The path segment this film is published under, unique per tenant. Stored,
     * not derived on read — see [Site.slug] for why. */
    val slug: String?,
    val durationMinutes: Int?,
    /** LSF age rating (SU / 13+ / 17+ / 21+), or null when not yet recorded. Displayed
     * here; *enforced* at hold time by the age attestation. */
    val classification: String?,
    val synopsis: String?,
    val posterUrl: String?,
    val trailerUrl: String?,
    val releaseDate: LocalDate?,
    val isActive: Boolean,
    val createdAt: Instant?,
)
