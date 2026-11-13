package com.protek.booking.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The seat files a tenant actually turns up with, pinned without a database.
 *
 * These read like a list of formats because that is what the feature is: the tenant wrote
 * the sheet, so the shapes here are the ones being accommodated rather than specified. The
 * cases that matter most are the ones where a wrong answer still looks like a working import —
 * a byte-order mark hiding the header, a semicolon-delimited export from an Indonesian Excel,
 * and a branch-wide file whose other studios must not land in this hall.
 */
class SeatMapCsvTest {

    companion object {
        private const val HALL = "Studio 1"

        private fun seats(csv: String): List<SeatMapPlan.Seat> =
            SeatMapCsv.parse(csv, HALL).seats

        private fun labels(csv: String): List<String> =
            seats(csv).map { it.label }
    }

    @Test
    fun aHeaderNamingRowAndSeatIsReadOneSeatPerLine() {
        val seats = seats("row,seat\nA,1\nA,2\nB,1")

        assertEquals(listOf("A1", "A2", "B1"), seats.map { it.label })
        assertEquals("A", seats[0].groupKey)
        assertEquals(2, seats[1].sequenceNo)
    }

    @Test
    fun anIndonesianExcelExportIsSemicolonDelimitedAndReadsTheSame() {
        // Excel writes the OS list separator, which is ";" on an Indonesian or European
        // Windows. Read as commas this is one column, and the operator is told their file has
        // no seats in it — which reads as our bug rather than their regional setting.
        assertEquals(listOf("A1", "A2"), labels("row;seat\nA;1\nA;2"))
    }

    @Test
    fun aTabSeparatedFileIsReadTheSameWay() {
        assertEquals(listOf("A1", "A2"), labels("row\tseat\nA\t1\nA\t2"))
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheHeader() {
        // "Save as CSV UTF-8" writes a BOM. Left in place, the first heading is U+FEFF
        // followed by "row", which matches nothing — so the header line is read as data and
        // the hall silently gains a seat called "row".
        val seats = seats("﻿row,seat\nA,1")

        assertEquals(1, seats.size)
        assertEquals("A1", seats[0].label)
    }

    @Test
    fun windowsLineEndingsAndTrailingBlankLinesAreIgnored() {
        assertEquals(listOf("A1", "A2"), labels("row,seat\r\nA,1\r\n\r\nA,2\r\n\r\n"))
    }

    @Test
    fun aGridDrawnInASpreadsheetIsReadRowByRow() {
        // No header: the first cell is the row, the rest are its seat numbers.
        assertEquals(listOf("A1", "A2", "A3", "B1", "B2", "B3"), labels("A,1,2,3\nB,1,2,3"))
    }

    @Test
    fun anEmptyCellInAGridIsASeatThatIsNotThere() {
        val seats = seats("C,1,2,,5,6")

        assertEquals(listOf("C1", "C2", "C5", "C6"), seats.map { it.label })
        // The numbers in the file are the numbers: the empty cell removes a seat, it does not
        // renumber the ones after it. Renumbering to invent an aisle would rename seats the
        // cinema's own staff read off the wall.
        assertEquals(listOf(1, 2, 5, 6), seats.map { it.sequenceNo })
    }

    @Test
    fun aBareListOfSeatLabelsDerivesItsRowsAndNumbers() {
        val seats = seats("A1\nA2\nB1")

        assertEquals(listOf("A", "A", "B"), seats.map { it.groupKey })
        assertEquals(listOf(1, 2, 1), seats.map { it.sequenceNo })
    }

    @Test
    fun aGridOfWholeLabelsIsReadAsLabelsRatherThanAsARowColumn() {
        // "A1,A2,A3" has no row column — the first cell is a seat like every other cell. The
        // decision is made for the whole file (does the first cell carry a trailing digit?),
        // not per line, so one odd line names itself instead of quietly changing the reading.
        val seats = seats("A1,A2,A3\nB1,B2")

        assertEquals(listOf("A1", "A2", "A3", "B1", "B2"), seats.map { it.label })
        assertEquals(listOf("A", "A", "A", "B", "B"), seats.map { it.groupKey })
    }

    @Test
    fun aBranchWideFileImportsOnlyTheStudioBeingConfigured() {
        // The failure this prevents is the expensive one: a chain hands over one sheet for the
        // whole branch, and importing all of it into whichever hall was open is thousands of
        // wrong seats with no cheap undo.
        // Unindented rather than trimIndent(), which would drop the Java text block's final newline.
        val parsed = SeatMapCsv.parse("""studio,row,seat
Studio 1,A,1
Studio 2,A,1
Studio 1,A,2
Studio 2,A,2
""", HALL)

        assertEquals(listOf("A1", "A2"), parsed.seats.map { it.label })
        assertEquals(2, parsed.linesForOtherHalls)
        assertTrue(parsed.note()!!.contains("Studio 2"), parsed.note())
    }

    @Test
    fun theStudioColumnMatchesLooselyEnoughToBeUsable() {
        // "STUDIO-1" and "Studio 1" are one studio. Exact matching would refuse a file for the
        // most cosmetic of reasons, and the operator has already told us which hall they mean.
        assertEquals(2, seats("studio,row,seat\nSTUDIO-1,A,1\nstudio1,A,2").size)
    }

    @Test
    fun aBlankStudioCellBelongsToTheHallBeingConfigured() {
        // Sheets often name the studio once and leave the rest of its block empty.
        assertEquals(listOf("A1", "A2"), labels("studio,row,seat\nStudio 1,A,1\n,A,2"))
    }

    @Test
    fun aFileForADifferentStudioIsRefusedAndNamesWhatItFound() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse("studio,row,seat\nStudio 4,A,1\nStudio 5,A,2", HALL)
        }

        assertTrue(e.message!!.contains("Studio 4"), e.message)
        assertTrue(e.message!!.contains("Studio 1"), "the message should name the hall being imported into: "
            + e.message)
    }

    @Test
    fun columnsWithNowhereToGoAreNamedRatherThanDroppedSilently() {
        // resource_unit stores a label, a row and a number. A seat class has nowhere to live
        // (attributes is read by no Java at all, and calendar pricing left by-category pricing unbuilt), so
        // the honest half is saying so before the import rather than after.
        val parsed = SeatMapCsv.parse("row,seat,class,price\nA,1,VIP,75000", HALL)

        assertEquals(1, parsed.seats.size)
        assertEquals(listOf("class", "price"), parsed.ignoredColumns)
        assertTrue(parsed.note()!!.contains("class"), parsed.note())
    }

    @Test
    fun aCleanFileHasNothingToSayBeyondTheCounts() {
        assertNull(SeatMapCsv.parse("row,seat\nA,1", HALL).note())
    }

    @Test
    fun aQuotedValueMayContainTheDelimiter() {
        val parsed = SeatMapCsv.parse("row,seat,class\nA,1,\"Sofa, double\"\nA,2,Regular", HALL)

        // The quoted comma must not shift the columns — the seats are the proof.
        assertEquals(listOf("A1", "A2"), parsed.seats.map { it.label })
    }

    @Test
    fun anUnclosedQuoteNamesItsLine() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse("row,seat\nA,1\n\"B,2", HALL)
        }

        assertTrue(e.message!!.startsWith("Line 3"), e.message)
    }

    @Test
    fun theSeatColumnMayCarryAWholeLabel() {
        val seats = seats("row,seat\nA,A12")

        assertEquals("A12", seats[0].label)
        assertEquals(12, seats[0].sequenceNo)
        assertEquals("A", seats[0].groupKey)
    }

    @Test
    fun aSeatColumnRepeatingTheLabelIsNotAReasonToRefuseTheFile() {
        // Exports do this: "seat" and "label" carry the same value. The label is authoritative,
        // so the number comes out of it rather than the file being refused over a duplicate.
        val seats = seats("row,seat,label\nA,A12,A12")

        assertEquals("A12", seats[0].label)
        assertEquals(12, seats[0].sequenceNo)
        assertEquals("A", seats[0].groupKey)
    }

    @Test
    fun aLabelThatIsNotRowPlusNumberIsKeptExactlyAsWritten() {
        // The whole reason to import rather than regenerate: these seats already have names,
        // painted on them, and the customer will be looking for that name.
        val seats = seats("label\nA-1\nA-2")

        assertEquals(listOf("A-1", "A-2"), seats.map { it.label })
        assertEquals("A", seats[0].groupKey)
        assertEquals(2, seats[1].sequenceNo)
    }

    @Test
    fun aLeadingZeroSurvivesInTheLabelAndIsGoneFromTheNumber() {
        // "A01" is what is on the seat; 1 is what the seat map orders by.
        val seats = seats("label\nA01\nA02")

        assertEquals(listOf("A01", "A02"), seats.map { it.label })
        assertEquals(listOf(1, 2), seats.map { it.sequenceNo })
    }

    @Test
    fun anExplicitRowColumnWinsOverTheOneImpliedByTheLabel() {
        assertEquals("Balkon", seats("row,label\nBalkon,A1")[0].groupKey)
    }

    @Test
    fun theSameSeatTwiceIsRefusedByTheSharedCollector() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse("row,seat\nA,1\nA,1", HALL)
        }

        assertTrue(e.message!!.contains("A1"), e.message)
        assertTrue(e.message!!.startsWith("Line 3"), e.message)
    }

    @Test
    fun twoLabelsInOnePositionOfOneRowAreRefused() {
        // The spreadsheet-shaped mistake: a column inserted part-way down the file. Both seats
        // would render on top of each other, since the map orders by (row, sequence).
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse("row,seat,label\nA,1,A1\nA,1,A-1", HALL)
        }

        assertTrue(e.message!!.contains("A1") && e.message!!.contains("A-1"), e.message)
    }

    @Test
    fun aSeatNumberedZeroIsRefusedByTheSameRuleTheGeneratorUses() {
        assertThrows(IllegalArgumentException::class.java) { SeatMapCsv.parse("row,seat\nA,0", HALL) }
    }

    @Test
    fun aSeatNumberWithNoRowSaysWhatToAddRatherThanGuessing() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse("seat\n1\n2", HALL)
        }

        assertTrue(e.message!!.contains("row"), e.message)
    }

    @Test
    fun aRowWithNoSeatsNamesItsLineInsteadOfImportingNothing() {
        // A totals line at the foot of a sheet, typically.
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse("A,1,2\nTotal,,", HALL)
        }

        assertTrue(e.message!!.startsWith("Line 2"), e.message)
    }

    @Test
    fun anEmptyFileIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SeatMapCsv.parse(null, HALL) }
        assertThrows(IllegalArgumentException::class.java) { SeatMapCsv.parse("   \n\n", HALL) }
    }

    @Test
    fun aFileOverTheHallLimitIsCaughtBeforeItBecomesAnInsert() {
        val oneEnormousRow = StringBuilder("A")
        for (seat in 1..2001) {
            oneEnormousRow.append(',').append(seat)
        }

        val e = assertThrows(IllegalArgumentException::class.java) {
            SeatMapCsv.parse(oneEnormousRow.toString(), HALL)
        }

        assertTrue(e.message!!.contains("limit"), e.message)
    }

    @Test
    fun aWheelchairColumnMarksItsSeatsInEitherLanguageAndABlankCellIsNo() {
        val seats = seats("row;seat;kursi roda\nA;1;ya\nA;2;\nA;3;Yes\nA;4;0\n")
        assertEquals(listOf(true, false, true, false), seats.map { it.accessible })
        // No column at all says nothing, which a relayout reads as "keep the seat's mark".
        assertEquals(listOf(null, null), seats("row,seat\nA,1\nA,2\n").map { it.accessible })
    }

    @Test
    fun aWheelchairCellThatIsNeitherYesNorNoIsRefusedNamingItsLine() {
        val e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            seats("row,seat,accessible\nA,1,yes\nA,2,x\n")
        }
        assertTrue(e.message!!.contains("\"x\""), e.message)
    }
}
