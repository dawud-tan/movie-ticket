package com.protek.booking.service

import com.protek.booking.domain.ResourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What a relayout does to seats that already exist, pinned without a database.
 *
 * The property under test throughout is **identity**: a seat whose label survives keeps
 * its `resource_unit_id`, so it keeps every booking, ticket and audit row pointing at it.
 * Everything else in relayout follows from that — the reason a retirement is a flag rather than
 * a delete, and the reason a returning label is the old seat rather than a new one.
 */
class SeatMapDiffTest {

    companion object {
        private val TENANT = UUID.randomUUID()
        private val POOL = UUID.randomUUID()

        private fun seat(label: String, row: String, number: Int, active: Boolean): ResourceUnit =
            ResourceUnit(UUID.randomUUID(), TENANT, POOL, label, row, number, active)

        private fun planned(label: String, row: String, number: Int): SeatMapPlan.Seat =
            SeatMapPlan.Seat(label, row, number)
    }

    @Test
    fun aSeatTheNewLayoutStillNamesInTheSamePlaceIsLeftAlone() {
        val diff = SeatMapDiff.of(
            listOf(seat("A1", "A", 1, true), seat("A2", "A", 2, true)),
            listOf(planned("A1", "A", 1), planned("A2", "A", 2)))

        assertEquals(2, diff.unchanged.size)
        assertTrue(diff.changesNothing())
        assertEquals(2, diff.seatCountAfterwards())
    }

    @Test
    fun aSeatTheNewLayoutDoesNotNameIsRetiredRatherThanDeleted() {
        val diff = SeatMapDiff.of(
            listOf(seat("A1", "A", 1, true), seat("A2", "A", 2, true)),
            listOf(planned("A1", "A", 1)))

        assertEquals(listOf("A2"), diff.retiring.map { it.label })
        assertTrue(diff.creating.isEmpty())
    }

    @Test
    fun aSeatThatMovesKeepsTheRowItAlreadyIs() {
        // The identity claim, in its smallest form: the diff hands back the existing seat, so
        // the UPDATE that follows carries its id and its bookings with it.
        val existing = seat("A5", "A", 5, true)

        val diff = SeatMapDiff.of(listOf(existing), listOf(planned("A5", "A", 3)))

        assertEquals(1, diff.moving.size)
        assertEquals(existing.resourceUnitId, diff.moving[0].seat.resourceUnitId)
        assertEquals(3, diff.moving[0].to.sequenceNo)
        assertTrue(diff.retiring.isEmpty(), "moving a seat is not retiring and re-creating it")
    }

    @Test
    fun aRetiredLabelTheLayoutWantsBackComesBackAsTheSameSeat() {
        val retired = seat("J1", "J", 1, false)

        val diff = SeatMapDiff.of(listOf(retired), listOf(planned("J1", "J", 1)))

        assertTrue(diff.creating.isEmpty(), "a second seat called J1 would be two seats with one name")
        assertEquals(1, diff.reinstating.size)
        assertEquals(retired.resourceUnitId, diff.reinstating[0].seat.resourceUnitId)
    }

    @Test
    fun aRetiredSeatTheLayoutStillDoesNotMentionIsNotTouchedAgain() {
        val diff = SeatMapDiff.of(
            listOf(seat("A1", "A", 1, true), seat("J1", "J", 1, false)),
            listOf(planned("A1", "A", 1)))

        assertTrue(diff.retiring.isEmpty(), "retiring an already-retired seat is an update saying nothing")
        assertTrue(diff.changesNothing())
    }

    @Test
    fun aLabelTheHallHasNeverHadIsCreated() {
        val diff = SeatMapDiff.of(
            listOf(seat("A1", "A", 1, true)),
            listOf(planned("A1", "A", 1), planned("B1", "B", 1)))

        assertEquals(listOf("B1"), diff.creating.map { it.label })
        assertEquals(2, diff.seatCountAfterwards())
    }

    @Test
    fun matchingIsExactSoARenameRetiresTheOldSeatAndCreatesANewOne() {
        // Worth pinning because it is the one case where "relayout" and "rename" differ: A1 and
        // A01 are two names, so the bookings on A1 stay on A1 and A1 is refused retirement while
        // anybody holds it. A rename that kept the bookings would need the operator to say which
        // seat is which, and nothing in a seat plan says that.
        val diff = SeatMapDiff.of(
            listOf(seat("A1", "A", 1, true)),
            listOf(planned("A01", "A", 1)))

        assertEquals(listOf("A01"), diff.creating.map { it.label })
        assertEquals(listOf("A1"), diff.retiring.map { it.label })
    }

    @Test
    fun aSeatWithNoSequenceNumberCountsAsMovingRatherThanUnchanged() {
        // addSeat has always allowed a null sequence_no. Treating null as "already correct"
        // would leave a seat the layout numbers sitting unnumbered, which is what orders the map.
        val diff = SeatMapDiff.of(
            listOf(ResourceUnit(UUID.randomUUID(), TENANT, POOL, "A1", "A", null, true)),
            listOf(planned("A1", "A", 1)))

        assertEquals(1, diff.moving.size)
        assertEquals(1, diff.moving[0].to.sequenceNo)
    }

    @Test
    fun twoSeatsWithOneLabelAreRefusedRatherThanGuessedBetween() {
        // Reachable with hand-entered data: addSeat takes one seat per POST and has never
        // checked for a collision. Picking one would silently retire the other.
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapDiff.of(
                listOf(seat("A1", "A", 1, true), seat("A1", "A", 1, true)),
                listOf(planned("A1", "A", 1)))
        }

        assertTrue(e.message!!.contains("A1"), e.message)
    }

    @Test
    fun anEmptyHallRelaysOutIntoOneThatIsPurelyCreated() {
        val diff = SeatMapDiff.of(listOf(), listOf(planned("A1", "A", 1)))

        assertEquals(1, diff.creating.size)
        assertFalse(diff.changesNothing())
    }

    @Test
    fun aPlanThatNamesNoWheelchairMarkKeepsTheSeatsAndOneThatNamesItChangesIt() {
        val marked = seat("A1", "A", 1, true).copy(accessible = true)
        val plain = seat("A2", "A", 2, true)

        val silent = SeatMapDiff.of(listOf(marked, plain), listOf(planned("A1", "A", 1), planned("A2", "A", 2)))
        assertTrue(silent.changesNothing(), "a generated layout knows nothing of the mark and must not clear it")

        val flipped = SeatMapDiff.of(listOf(marked, plain), listOf(
            SeatMapPlan.Seat("A1", "A", 1, false), SeatMapPlan.Seat("A2", "A", 2, true)))
        assertEquals(listOf("A1", "A2"), flipped.remarking.map { it.seat.label })
        assertEquals(2, flipped.seatCountAfterwards())
        assertTrue(flipped.moving.isEmpty() && flipped.unchanged.isEmpty())
    }
}
