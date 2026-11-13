package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

@JvmRecord
data class ReservationGroup(
    val reservationGroupId: UUID?,
    /** The short, quotable reference for this booking — what a customer reads down
     * the phone and what staff type into the booking lookup. Unique per tenant, and
     * deliberately not the entry credential: that is `Reservation.admissionToken`,
     * which is per seat and unguessable. */
    val bookingCode: String?,
    val tenantId: UUID?,
    val resourcePoolId: UUID?,
    val customerId: UUID?,
    val requestedUnitCount: Int,
    val status: String?,
    val failedResourceUnitId: UUID?,
    val createdAt: Instant?,
    val confirmedAt: Instant?,
)
