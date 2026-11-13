package com.protek.booking.service

import com.protek.booking.domain.ResourceUnit

/**
 * What applying a whole seat plan to a hall that *already has seats* would change —
 * the relayout that came after CSV import.
 *
 * Generation and import are additive and refuse a label that already exists, for a reason
 * that has not changed: `reservation` references `resource_unit`, so a seat map
 * cannot be replaced by deleting rows. A studio that is genuinely re-seated needs a different
 * operation, and this is its arithmetic — computed here, without a database, because what it
 * decides (which seat keeps its identity, which stops being sellable) is the whole of the
 * feature and the part worth being able to test exhaustively.
 *
 * **Seats are matched by label, and the label is the seat's identity.** A seat whose
 * label survives the relayout keeps its `resource_unit_id` — so every reservation, ticket
 * and audit row pointing at it still points at it, and a seat that merely moves within its row
 * takes its history with it. A label the new layout does not mention is **retired**
 * (`is_active = false`), never deleted: the row has to outlive the layout because
 * yesterday's ticket still names it.
 *
 * Four things can happen to a seat, and the preview says which:
 *
 *  - **unchanged** — the label is in both, at the same row and number.
 *  - **moving** — the label is in both, at a different row or number. One UPDATE; the
 *    identity is untouched, which is what keeps the bookings.
 *  - **reinstating** — the label exists but was retired by an earlier relayout, and the
 *    new layout wants it back. It comes back as *the same seat* rather than a second
 *    one with the same name — which is why `findLabelsByPool` has always included
 *    retired seats.
 *  - **retiring** — an active label the new layout does not have.
 *  - **creating** — a label the hall has never had.
 *
 * What this class deliberately does not decide is whether a retirement is *allowed*:
 * a seat somebody is holding or has paid for cannot be retired, and that is a question about
 * reservations, answered in `CatalogService` inside the transaction that does the work.
 */
// A @JvmRecord data class: the tests and the relayout template read creating(),
// retiring() and the rest as record accessors.
@JvmRecord
data class SeatMapDiff(val creating: List<SeatMapPlan.Seat>,
                       val moving: List<Repositioning>,
                       val reinstating: List<Repositioning>,
                       val retiring: List<ResourceUnit>,
                       val unchanged: List<ResourceUnit>,
                       /** Same place, only the accessible mark changes. */
                       val remarking: List<Repositioning> = listOf()) {

    /** An existing seat and where the new layout puts it. */
    @JvmRecord
    data class Repositioning(val seat: ResourceUnit, val to: SeatMapPlan.Seat)

    /** True when the new layout describes exactly the hall that is already there — worth saying
     * out loud on the preview, because the alternative reading of "0 seats created" is that
     * something went wrong. */
    fun changesNothing(): Boolean =
        creating.isEmpty() && moving.isEmpty() && reinstating.isEmpty() && retiring.isEmpty() &&
            remarking.isEmpty()

    fun seatCountAfterwards(): Int = unchanged.size + moving.size + reinstating.size + creating.size +
        remarking.size

    companion object {

        @JvmStatic
        fun of(existing: List<ResourceUnit>, planned: List<SeatMapPlan.Seat>): SeatMapDiff {
            val byLabel = indexByLabel(existing)
            val plannedLabels = LinkedHashSet<String?>()

            val creating = ArrayList<SeatMapPlan.Seat>()
            val moving = ArrayList<Repositioning>()
            val reinstating = ArrayList<Repositioning>()
            val unchanged = ArrayList<ResourceUnit>()
            val remarking = ArrayList<Repositioning>()

            for (seat in planned) {
                plannedLabels.add(seat.label)
                val existingSeat = byLabel[seat.label]
                if (existingSeat == null) {
                    creating.add(seat)
                } else if (!existingSeat.isActive) {
                    reinstating.add(Repositioning(existingSeat, seat))
                } else if (samePosition(existingSeat, seat)) {
                    // A plan that names no mark (null) keeps the seat's; one that names it decides.
                    if (seat.accessible == null || seat.accessible == existingSeat.accessible) {
                        unchanged.add(existingSeat)
                    } else {
                        remarking.add(Repositioning(existingSeat, seat))
                    }
                } else {
                    moving.add(Repositioning(existingSeat, seat))
                }
            }

            val retiring = ArrayList<ResourceUnit>()
            for (seat in existing) {
                // Already-retired seats the new layout still does not mention are left exactly as
                // they are: retiring them again would be an UPDATE that says nothing, and the
                // preview would claim work it is not doing.
                if (seat.isActive && !plannedLabels.contains(seat.label)) {
                    retiring.add(seat)
                }
            }

            // java.util.List.copyOf, as the Java: unmodifiable.
            return SeatMapDiff(java.util.List.copyOf(creating), java.util.List.copyOf(moving),
                java.util.List.copyOf(reinstating), java.util.List.copyOf(retiring),
                java.util.List.copyOf(unchanged), java.util.List.copyOf(remarking))
        }

        /**
         * @throws IllegalArgumentException when the hall already holds two seats with one label.
         *         Matching is by label, so there would be no answer to which of them the new layout
         *         means — and picking one would silently retire somebody's seat. `addSeat`
         *         (one seat per POST) has never checked for a collision, so this is reachable with
         *         hand-entered data even though neither the generator nor the import can produce it.
         */
        private fun indexByLabel(existing: List<ResourceUnit>): Map<String?, ResourceUnit> {
            val byLabel = LinkedHashMap<String?, ResourceUnit>()
            for (seat in existing) {
                val clash = byLabel.putIfAbsent(seat.label, seat)
                if (clash != null) {
                    throw IllegalArgumentException("This hall already has two seats labelled " +
                            seat.label + ", so a relayout cannot tell which one the new layout means. " +
                            "Rename or remove one of them first — nothing has been changed.")
                }
            }
            return byLabel
        }

        private fun samePosition(seat: ResourceUnit, planned: SeatMapPlan.Seat): Boolean =
            // planned.groupKey!!: the Java's planned.groupKey().equals(...) threw on a null row.
            planned.groupKey!! == seat.groupKey &&
                    seat.sequenceNo != null && seat.sequenceNo == planned.sequenceNo
    }
}
