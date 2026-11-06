package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/**
 * A customer's position in the booking flow — the `booking_ref` in
 * `/booking?booking_ref=…`.
 *
 * It holds **no seat and blocks nothing**. That separation is the point: the thing that
 * reserves inventory is a `reservation` row created by the `EXCLUDE` constraint at
 * hold time, and a draft that also claimed seats would be a second, weaker claim on the
 * same rows with no constraint behind it.
 *
 * What it exists to survive is the booking page's own date and time fields. Changing either
 * moves [resourcePoolSessionId] and leaves [bookingRef] where it is, so the
 * customer's address bar keeps naming the same booking while they shop around the schedule.
 *
 * Keyed on `app_user`, not `customer`: a customer row is only created at hold
 * time, and the person opening their first draft has not held anything yet. See the booking_draft table.
 */
@JvmRecord
data class BookingDraft(
    val bookingRef: UUID?,
    val tenantId: UUID?,
    val appUserId: UUID?,
    val resourcePoolSessionId: UUID?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val expiresAt: Instant?,
)
