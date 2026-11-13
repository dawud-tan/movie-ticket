package com.protek.booking.service

/**
 * What a generated seat map would do, shown before it is committed.
 *
 * A preview step is not decoration here. The generator writes hundreds of rows at once and
 * `resource_unit` rows are referenced by `reservation`, so there is no cheap undo —
 * seeing "231 seats across 12 rows, and A1-A10 already exist" beforehand is what keeps a typo
 * in a range from becoming a data-repair job.
 *
 * @param seats     every seat the plan would create, in row then seat order
 * @param conflicts labels that already exist in this hall; non-empty means the plan is refused
 * @param rowCount  distinct rows, for a summary line that reads like the thing being described
 * @param note      anything the operator has to be told beyond the counts, or null. CSV import
 *                  is the only source of one today: a file can carry a column we cannot store
 *                  or lines belonging to another studio, and both are things that look like a
 *                  clean import right up until the seats are missing.
 */
// A @JvmRecord data class: the tests and the admin templates read seats(),
// conflicts(), rowCount() and note().
@JvmRecord
data class SeatMapPreview(val seats: List<SeatMapPlan.Seat>, val conflicts: List<String>, val rowCount: Int,
                          val note: String?) {

    /** The generator and clone paths have nothing extra to say. */
    constructor(seats: List<SeatMapPlan.Seat>, conflicts: List<String>, rowCount: Int) :
            this(seats, conflicts, rowCount, null)

    fun withNote(note: String?): SeatMapPreview = SeatMapPreview(seats, conflicts, rowCount, note)

    fun hasConflicts(): Boolean = !conflicts.isEmpty()

    fun seatCount(): Int = seats.size
}
