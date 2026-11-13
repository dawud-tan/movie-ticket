package com.protek.booking.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The irregular layouts a real auditorium has, pinned without a database.
 *
 * These cases are the reason the generator exists as a parser rather than a loop over
 * "rows × seats": the schema requires `sequence_no` stay a plain integer, so an aisle has to
 * be a real gap in the numbering, and getting that right once here is the alternative to
 * getting it right per hand-entered seat.
 */
class SeatMapPlanTest {

    @Test
    fun aRegularGridBecomesOneSeatPerPosition() {
        val seats = SeatMapPlan.parse("A: 1-3\nB: 1-3")

        assertEquals(6, seats.size)
        assertEquals(listOf("A1", "A2", "A3", "B1", "B2", "B3"),
            seats.map { it.label })
        assertEquals("A", seats[0].groupKey)
        assertEquals(1, seats[0].sequenceNo)
    }

    @Test
    fun severalRangesOnOneRowLeaveAnAisleAsARealGapInTheSequence() {
        val seats = SeatMapPlan.parse("C: 1-4, 7-9")

        assertEquals(listOf("C1", "C2", "C3", "C4", "C7", "C8", "C9"),
            seats.map { it.label })
        // The gap is the point: seats 4 and 7 must not be numerically adjacent, or a future
        // contiguous-block search would happily seat a group across the aisle.
        assertEquals(4, seats[3].sequenceNo)
        assertEquals(7, seats[4].sequenceNo)
    }

    @Test
    fun rowsMayDifferInLength() {
        val seats = SeatMapPlan.parse("A: 1-2\nB: 1-5")

        // Stream.count() is a long, which Java widened the expected int to.
        assertEquals(2L, seats.stream().filter { it.groupKey == "A" }.count())
        assertEquals(5L, seats.stream().filter { it.groupKey == "B" }.count())
    }

    @Test
    fun aRowLabelThatIsSkippedSimplyIsNotCreated() {
        // Cinemas routinely omit row "I" because it reads as "1". That needs no syntax.
        val seats = SeatMapPlan.parse("H: 1-2\nJ: 1-2")

        assertEquals(listOf("H", "H", "J", "J"), seats.map { it.groupKey })
    }

    @Test
    fun aSingleSeatNeedsNoRange() {
        val seats = SeatMapPlan.parse("A: 5")

        assertEquals(1, seats.size)
        assertEquals("A5", seats[0].label)
        assertEquals(5, seats[0].sequenceNo)
    }

    @Test
    fun blankLinesAndCommentsAreIgnored() {
        val seats = SeatMapPlan.parse("# front section\nA: 1-2\n\nB: 1-2   # by the door\n")

        assertEquals(4, seats.size)
        assertEquals("B", seats[3].groupKey)
    }

    @Test
    fun multiCharacterRowLabelsSurviveIntact() {
        val seats = SeatMapPlan.parse("AA: 1-2")

        assertEquals(listOf("AA1", "AA2"), seats.map { it.label })
        assertEquals("AA", seats[0].groupKey)
    }

    @Test
    fun theSameSeatListedTwiceIsRejectedRatherThanDuplicated() {
        // Two seats labelled A5 would render as one on the seat map while occupying two rows.
        val e = assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("A: 1-5\nA: 5-8") }

        assertTrue(e.message!!.contains("A5"), "message should name the duplicated seat: " + e.message)
    }

    @Test
    fun aBackwardsRangeIsRejectedWithTheCorrectionInTheMessage() {
        val e = assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("A: 9-3") }

        assertTrue(e.message!!.contains("3-9"), "message should suggest the fix: " + e.message)
    }

    @Test
    fun seatNumbersBelowOneAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("A: 0-4") }
    }

    @Test
    fun aLineWithoutAColonNamesItsOwnLineNumber() {
        val e = assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("A: 1-4\nB 1-4") }

        assertTrue(e.message!!.startsWith("Line 2"), "message should locate the error: " + e.message)
    }

    @Test
    fun aNonNumericSeatIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("A: 1-four") }
    }

    @Test
    fun anEmptyPlanIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("   ") }
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse(null) }
    }

    @Test
    fun anImplausiblyLargeRangeIsCaughtBeforeItBecomesAnInsert() {
        // The failure this prevents is a mistyped "A: 1-100000" reaching the database.
        val e = assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.parse("A: 1-100000") }

        assertTrue(e.message!!.contains("limit"), e.message)
    }

    @Test
    fun uniformExpandsALetterRangeIntoOneLinePerRow() {
        val spec = SeatMapPlan.uniform("A-C", 4)

        assertEquals("A: 1-4\nB: 1-4\nC: 1-4\n", spec)
        assertEquals(12, SeatMapPlan.parse(spec).size)
    }

    @Test
    fun uniformAcceptsAnExplicitRowListSoASkippedLetterIsExpressible() {
        val spec = SeatMapPlan.uniform("A,B,J", 2)

        assertEquals(listOf("A", "B", "J"),
            SeatMapPlan.parse(spec).map { it.groupKey }.distinct())
    }

    @Test
    fun uniformDoesNotSilentlySkipRowI() {
        // Deliberate: guessing at cinema convention would be magic, and the generated spec is
        // editable before it is committed.
        assertTrue(SeatMapPlan.uniform("H-J", 1).contains("I: 1-1"))
    }

    @Test
    fun uniformRejectsABackwardsOrMalformedRowRange() {
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.uniform("K-A", 4) }
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.uniform("AA-ZZ", 4) }
        assertThrows(IllegalArgumentException::class.java) { SeatMapPlan.uniform("A-C", 0) }
    }
}
