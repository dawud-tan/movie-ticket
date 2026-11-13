package com.protek.booking.web.dto

import com.protek.booking.domain.SeatMapEntry

/** Seats for one seat map, grouped by `group_key` (row) for grid rendering. */
@JvmRecord
data class SeatRow(val rowLabel: String?, val seats: List<SeatMapEntry>?)
