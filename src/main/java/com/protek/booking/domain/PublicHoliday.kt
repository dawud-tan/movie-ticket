package com.protek.booking.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One day on a tenant's `hari libur nasional` list.
 *
 * Exists because a common cinema top price band is "weekend, public holiday, *or the day before
 * one*", and no date arithmetic yields that second clause — most Indonesian national
 * holidays are lunar and all of them are set annually by decree, so the list has to be
 * maintained rather than computed.
 */
@JvmRecord
data class PublicHoliday(
    val publicHolidayId: UUID?,
    val tenantId: UUID?,
    /** A calendar day, never an instant: 17 Agustus is 17 Agustus in every timezone the
     * tenant operates in, at different absolute times. */
    val holidayDate: LocalDate?,
    val name: String?,
    val createdAt: Instant?,
)
