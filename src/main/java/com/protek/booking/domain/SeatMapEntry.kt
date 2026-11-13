package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/**
 * One seat's row in a live seat map — computed directly against `held`/`confirmed`
 * reservation rows for the showing, never a static or cached image.
 */
@JvmRecord
data class SeatMapEntry(
    val resourceUnitId: UUID?,
    val label: String?,
    val groupKey: String?,
    val sequenceNo: Int?,
    val seatStatus: String?,   // available | held | confirmed
    val reservationId: UUID?,  // null when AVAILABLE
    val holdExpiresAt: Instant?, // null unless seatStatus = HELD
    val accessible: Boolean = false, // a wheelchair space, marked only
)
