package com.protek.booking.web

import com.protek.booking.domain.SeatMapEntry
import com.protek.booking.web.dto.SeatRow

/** Shared between the customer seat-map view and the tenant-staff reservation-management
 * view — both render the same computed seat map, just with different actions per seat. */
object SeatMapSupport {

    @JvmStatic
    fun groupByRow(seats: List<SeatMapEntry>): List<SeatRow> {
        val byRow = LinkedHashMap<String?, MutableList<SeatMapEntry>>()
        for (seat in seats) {
            byRow.computeIfAbsent(seat.groupKey) { ArrayList() }.add(seat)
        }
        return byRow.entries.map { e -> SeatRow(e.key, e.value) }
    }
}
