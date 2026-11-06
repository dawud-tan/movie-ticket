package com.protek.booking.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JvmRecord
data class Reservation(
    val reservationId: UUID?,
    val tenantId: UUID?,
    val reservationGroupId: UUID?,
    val resourceUnitId: UUID?,
    val resourcePoolSessionId: UUID?,
    val policyDocumentId: UUID?,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val status: String?,
    val priceAmount: BigDecimal?,
    val priceCurrency: String?,
    val holdExpiresAt: Instant?,
    val createdByActor: String?,
    val confirmedAt: Instant?,
    val cancelledAt: Instant?,
    val cancellationReason: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    /** The barcode value presented at the door. Distinct from every id the booking
     * already has, on purpose — see the migration. Present from insert, which is not the
     * same as being admissible: [admittedAt] and [status] decide that. */
    val admissionToken: String?,
    /** NULL until an usher scans this seat in. Deliberately orthogonal to [status]
     * rather than a value of it: an 'admitted' status would fall outside the EXCLUDE
     * constraint's predicate and re-release a seat that is currently being sat in. */
    val admittedAt: Instant?,
    val admittedByAppUserId: UUID?,
) {

    /** Whether this seat has already been walked through. Named for the template and the scan
     * result, both of which ask the question rather than the timestamp. */
    fun isAdmitted(): Boolean = admittedAt != null
}
