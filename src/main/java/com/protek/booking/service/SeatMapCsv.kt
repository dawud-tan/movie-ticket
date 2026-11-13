package com.protek.booking.service

import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import java.util.EnumMap
import java.util.regex.Pattern

/**
 * Reads a studio's existing seat layout out of a spreadsheet export.
 *
 * Why this exists now, when the generator did not need it: the generator answers "describe
 * the hall you want", and a tenant arriving with a layout already written down is answering a
 * different question — they want the seats they already have, named the way they already name
 * them. That was the condition for building this, and it is the case this class serves.
 * Everything downstream is unchanged: the seats it produces go through [SeatMapPlan]'s
 * collector, `CatalogService.previewSeats`' conflict check and the same single-transaction
 * batch insert, so an imported seat is the same row as a generated one.
 *
 * **It reads the file the tenant has, not a file we specify.** Three shapes, decided per
 * file and in this order:
 *
 *  1. **A header naming any column we understand** (`row`/`baris`,
 *    `seat`/`nomor`/`no`, `label`, `studio`/`hall`) —
 *    then every later line is one seat, and columns we have no home for are named back to
 *    the operator rather than dropped silently.
 *  2. **No header, and the first cell of every line has no trailing digits** — a grid
 *    drawn in a spreadsheet: the first cell is the row and the rest are its seat numbers
 *    (`A,1,2,3`). An empty cell is a seat that is not there.
 *  2. **No header, and the first cells do carry digits** — every cell is a whole seat
 *    label (`A1,A2,A3`, or one label per line).
 *
 * Traps this pays for. These are documented Excel behaviours, designed against rather than
 * discovered here — each one produces a wrong answer that still looks like a working import,
 * which is why they are worth the code:
 *
 *  - **Excel on an Indonesian locale writes `;`, not `,`** — the list
 *    separator follows the OS regional setting. A comma-only reader sees one giant column
 *    and reports a file with no seats in it, which reads as our bug, not their locale. The
 *    delimiter is sniffed from the first line: `;`, tab or comma.
 *  - **"Save as CSV UTF-8" writes a byte-order mark**, so the first header cell arrives
 *    as U+FEFF followed by `row` and matches nothing. The header is then not
 *    detected, the header line is read as data, and the import silently gains a seat called
 *    "row". Stripped below.
 *  - **One file often covers the whole branch.** A chain hands over one sheet with a
 *    studio column, and importing all of it into the hall the operator happens to have open
 *    is thousands of wrong seats with no cheap undo. A studio column is therefore a filter,
 *    not an ignorable extra: rows for other studios are left alone and counted, and a file
 *    that names no studio matching this hall is refused outright.
 *
 * **The numbers in the file are the numbers.** An empty cell removes a seat; it does not
 * renumber the seats after it. So a hall whose own numbering runs straight through an aisle
 * imports without a gap in `sequence_no` — that is a true statement about their
 * numbering, and the text spec cannot express it either. Renumbering to invent the gap would
 * silently rename seats the cinema's own staff read off a wall.
 *
 * Pure and dependency-free, like [SeatMapPlan]: no CSV library, because the format here
 * is a line, a delimiter and optional quotes, so one would buy a parser that already fits in this
 * file and cost a version to track.
 */
// An object with @JvmStatic members. Java's string rules matter most here, because this
// reads files: the line split is Pattern.split with Java's -1 limit, every trim is javaTrim and
// every isBlank is isJavaBlank, so a cell holding a non-breaking space or a control character
// reads exactly as it did.
object SeatMapCsv {

    /** What a column means to us. Anything else is named back to the operator, not imported. */
    private enum class Role { ROW, NUMBER, LABEL, HALL, ACCESSIBLE }

    private val HEADINGS: MutableMap<Role, Set<String>> = EnumMap(Role::class.java)

    private val LINE_BREAK: Pattern = Pattern.compile("\\R")

    init {
        // Indonesian and English, because the tenant writes the sheet, not us. Matching is on
        // the normalised cell (lowercased, non-alphanumerics dropped), so "Seat No." and
        // "seat_no" both land here without needing their own entry.
        HEADINGS[Role.ROW] = java.util.Set.of("row", "rowlabel", "baris", "deret")
        HEADINGS[Role.NUMBER] = java.util.Set.of("seat", "seatno", "seatnumber", "number", "no", "nomor",
            "kursi", "nokursi", "nomorkursi", "seq", "sequence", "sequenceno", "position")
        HEADINGS[Role.LABEL] = java.util.Set.of("label", "seatlabel", "seatid", "seatcode", "kode", "kodekursi", "nama")
        HEADINGS[Role.HALL] = java.util.Set.of("hall", "studio", "screen", "auditorium", "theatre",
            "theater", "ruang", "ruangan")
        // A wheelchair space, marked only.
        HEADINGS[Role.ACCESSIBLE] = java.util.Set.of("accessible", "wheelchair", "kursiroda",
            "aksesibel", "difabel")
    }

    /**
     * One parsed file.
     *
     * @param seats             the seats to create, in file order
     * @param ignoredColumns    header columns we had nowhere to put, named so the operator can
     *                          see what did not come across
     * @param hallColumn        the heading of the studio column, when the file had one
     * @param linesForOtherHalls lines that named a different studio and were left alone
     * @param otherHallNames    the studios those lines named, so the operator can see the file
     *                          was understood rather than half-read
     */
    @JvmRecord
    data class Import(val seats: List<SeatMapPlan.Seat>, val ignoredColumns: List<String>,
                      val hallColumn: String?, val linesForOtherHalls: Int, val otherHallNames: List<String>) {

        /**
         * What the preview should say beyond the seat count — null when the file mapped
         * cleanly and there is nothing to warn about.
         *
         * Both halves are about silence: a dropped column and a skipped line are exactly
         * the two ways an import can look like it worked and not have.
         */
        fun note(): String? {
            val parts = ArrayList<String>()
            if (linesForOtherHalls > 0) {
                parts.add(linesForOtherHalls.toString() + (if (linesForOtherHalls == 1) " line names" else " lines name") +
                        " another studio in the \"" + hallColumn + "\" column (" +
                        java.lang.String.join(", ", otherHallNames) + "), and stayed with it.")
            }
            if (!ignoredColumns.isEmpty()) {
                // Deliberately not stored anywhere. resource_unit.attributes holds one key the
                // code reads, `accessible` (the column above); writing a seat class beside it
                // would claim a capability nothing implements, the same mistake a NOT NULL
                // film.classification column would have made. Naming the column is the honest half.
                parts.add("Not imported: " + java.lang.String.join(", ", ignoredColumns) +
                        ". A seat is stored as a label, a row, a number and a wheelchair mark — a " +
                        "seat class or a per-seat price has nowhere to go yet.")
            }
            return if (parts.isEmpty()) null else java.lang.String.join(" ", parts)
        }
    }

    /**
     * @param hallName the hall being configured, used only to decide which lines of a
     *                 multi-studio file belong to it
     * @throws IllegalArgumentException with a message naming the offending line, for the same
     *         reason [SeatMapPlan.parse] does: this is a file an operator can fix.
     */
    @JvmStatic
    fun parse(csv: String?, hallName: String?): Import {
        if (csv == null || csv.isJavaBlank()) {
            throw IllegalArgumentException(
                "That file is empty. Export the seat list with one seat per line, or one row per line.")
        }
        // "CSV UTF-8" from Excel leads with a BOM; left in place it hides the header.
        val text = if (csv[0] == '﻿') csv.substring(1) else csv

        val lines = readLines(text)
        if (lines.isEmpty()) {
            throw IllegalArgumentException("That file has no lines in it.")
        }

        val columns = headerOf(lines[0])
        return if (columns.isEmpty())
            fromGrid(lines)
        else
            fromColumns(lines, columns, hallName)
    }

    // --- The three shapes ----------------------------------------------------------------

    /** A file with a header: one seat per line, columns say which is which. */
    private fun fromColumns(lines: List<Line>, columns: Map<Role, Int>, hallName: String?): Import {
        val ignored = ignoredColumns(lines[0], columns)
        val hallColumn = columns[Role.HALL]
        val otherHalls = LinkedHashSet<String>()
        var skipped = 0

        val seats = SeatMapPlan.Collector()
        for (line in lines.subList(1, lines.size)) {
            if (line.isBlank()) {
                continue
            }
            if (hallColumn != null) {
                val hall = line.at(hallColumn)
                // A blank studio cell is taken as "the hall being configured": the operator
                // chose it, and refusing a half-filled column would block the common case of a
                // sheet where only the first row of each studio names it.
                if (hall != null && !sameHall(hall, hallName)) {
                    otherHalls.add(hall)
                    skipped++
                    continue
                }
            }
            addFromColumns(seats, line, columns)
        }

        if (seats.isEmpty() && skipped > 0) {
            throw IllegalArgumentException("Every line in this file names a different studio (" +
                    java.lang.String.join(", ", first(otherHalls, 5)) + ") — none of them \"" + hallName +
                    "\". Import the file into the hall it names, or rename this hall to match it.")
        }
        if (seats.isEmpty()) {
            throw IllegalArgumentException(
                "That file has a header but no seats under it.")
        }
        return Import(seats.finish(), ignored,
            if (hallColumn == null) null else lines[0].at(hallColumn),
            skipped, first(otherHalls, 5))
    }

    private fun addFromColumns(seats: SeatMapPlan.Collector, line: Line, columns: Map<Role, Int>) {
        val where = line.where()
        val row = value(line, columns[Role.ROW])
        val number = value(line, columns[Role.NUMBER])
        val label = value(line, columns[Role.LABEL])
        // A column that is there decides for every line (a blank cell is "no"); no column, null.
        val accessible = columns[Role.ACCESSIBLE]?.let { flag(line.at(it), where) }

        if (label != null) {
            // The label is authoritative when both are present: a "seat" column beside a label
            // column sometimes repeats the whole label rather than the number, and refusing the
            // file over that would be refusing it for nothing.
            seats.add(label, row ?: rowOf(label, where),
                if (number != null && isDigits(number)) number(number, where) else numberIn(label, where), where,
                accessible)
            return
        }
        if (number != null) {
            if (isDigits(number)) {
                if (row == null) {
                    throw IllegalArgumentException(where + ": seat " + number + " has no row. Add a row " +
                            "column, or give whole labels like \"A" + number + "\".")
                }
                seats.add(row + number, row, number(number, where), where, accessible)
            } else {
                // Some exports put the whole label under a "seat" heading. Reading it as a
                // label rather than refusing it costs nothing and saves an edit.
                seats.add(number, row ?: rowOf(number, where), numberIn(number, where), where, accessible)
            }
            return
        }
        if (row != null) {
            throw IllegalArgumentException(where + ": row " + row + " with no seat number.")
        }
        throw IllegalArgumentException(where + ": no seat on this line.")
    }

    /** A file with no header: a grid drawn in a spreadsheet, or a bare list of labels. */
    private fun fromGrid(lines: List<Line>): Import {
        // Decided once for the file rather than per line, so one odd line produces an error
        // naming itself instead of quietly switching how the rest is read. A first cell with a
        // trailing digit ("A1") is a seat; one without ("A") is a row heading.
        val firstCellIsRow = lines.stream().allMatch { line -> trailingDigits(line.at(0)) == null }

        val seats = SeatMapPlan.Collector()
        for (line in lines) {
            if (line.isBlank()) {
                continue
            }
            val where = line.where()
            if (!firstCellIsRow) {
                for (cell in line.fields) {
                    if (!cell.isJavaBlank()) {
                        seats.add(cell, rowOf(cell, where), numberIn(cell, where), where)
                    }
                }
                continue
            }
            val row = line.at(0)
                ?: throw IllegalArgumentException(where + ": the first column is the row label, and it is empty.")
            var added = 0
            for (i in 1 until line.fields.size) {
                val cell = line.at(i)
                    ?: continue // An aisle, a shorter row, or Excel padding the line out to the widest one.
                if (isDigits(cell)) {
                    seats.add(row + cell, row, number(cell, where), where)
                } else {
                    seats.add(cell, row, numberIn(cell, where), where)
                }
                added++
            }
            if (added == 0) {
                throw IllegalArgumentException(where + ": row " + row + " lists no seats. Delete the line, " +
                        "or add a header row so this column can be read as something other than a row label.")
            }
        }
        if (seats.isEmpty()) {
            throw IllegalArgumentException("That file describes no seats.")
        }
        return Import(seats.finish(), java.util.List.of(), null, 0, java.util.List.of())
    }

    // --- Header -------------------------------------------------------------------------

    /** Empty when the first line names nothing we understand, which is what "no header" means. */
    private fun headerOf(first: Line): Map<Role, Int> {
        val columns: MutableMap<Role, Int> = EnumMap(Role::class.java)
        for (i in 0 until first.fields.size) {
            val role = roleOf(first.at(i))
            if (role != null) {
                columns.putIfAbsent(role, i) // First column of a kind wins; a repeat is an extra.
            }
        }
        return columns
    }

    private fun roleOf(heading: String?): Role? {
        if (heading == null) {
            return null
        }
        val normalised = normalise(heading)
        for ((key, value) in HEADINGS) {
            if (value.contains(normalised)) {
                return key
            }
        }
        return null
    }

    private fun ignoredColumns(header: Line, columns: Map<Role, Int>): List<String> {
        val ignored = ArrayList<String>()
        for (i in 0 until header.fields.size) {
            val heading = header.at(i)
            if (heading != null && !columns.containsValue(i)) {
                ignored.add(heading)
            }
        }
        return ignored
    }

    // --- Cells --------------------------------------------------------------------------

    private fun value(line: Line, column: Int?): String? =
        if (column == null) null else line.at(column)

    /** The accessible column's cell, in either language. Anything else is refused, naming the line:
     * guessing "x" or "✓" would mark a seat nobody meant to. */
    private fun flag(cell: String?, where: String): Boolean =
        when (cell?.javaTrim()?.lowercase()) {
            null, "", "no", "n", "tidak", "t", "0", "false" -> false
            "yes", "y", "ya", "1", "true" -> true
            else -> throw IllegalArgumentException(where + ": \"" + cell + "\" in the wheelchair column. " +
                    "Write yes/ya/1/true for a wheelchair space, or leave it empty.")
        }

    /** A row and a seat number, out of a whole label: "A12" and "A-12" are both row A, seat 12. */
    private fun rowOf(label: String, where: String): String {
        val digits = trailingDigits(label)
            ?: throw IllegalArgumentException(where + ": \"" + label + "\" has no seat number in it. Give a " +
                    "label like \"A12\", or add row and seat columns with a header.")
        var row = label.substring(0, label.length - digits.length)
        // "A-12", "A 12" and "A_12" are all row A. The label keeps the separator; only the row
        // this seat is grouped under drops it.
        while (!row.isEmpty() && !Character.isLetterOrDigit(row[row.length - 1])) {
            row = row.substring(0, row.length - 1)
        }
        if (row.isJavaBlank()) {
            throw IllegalArgumentException(where + ": \"" + label + "\" is a seat number with no row. Add a " +
                    "row column, or label the seat with its row, like \"A" + label + "\".")
        }
        return row
    }

    private fun numberIn(label: String, where: String): Int {
        val digits = trailingDigits(label)
            ?: throw IllegalArgumentException(where + ": \"" + label + "\" has no seat number in it.")
        return number(digits, where)
    }

    private fun number(text: String, where: String): Int {
        try {
            // Leading zeros are dropped here and kept in the label: "A01" is seat 1 of row A and
            // is still called A01, because that is what is painted on it.
            return Integer.parseInt(text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException(where + ": \"" + text + "\" is not a seat number.")
        }
    }

    /** Null rather than "" when the value does not end in digits, so callers can branch on it. */
    private fun trailingDigits(value: String?): String? {
        if (value == null || value.isEmpty()) {
            return null
        }
        var i = value.length
        while (i > 0 && Character.isDigit(value[i - 1])) {
            i--
        }
        return if (i == value.length) null else value.substring(i)
    }

    private fun isDigits(value: String?): Boolean {
        if (value == null || value.isEmpty()) {
            return false
        }
        for (i in 0 until value.length) {
            if (!Character.isDigit(value[i])) {
                return false
            }
        }
        return true
    }

    private fun sameHall(fromFile: String, hallName: String?): Boolean =
        hallName != null && normalise(fromFile) == normalise(hallName)

    /** Lowercased with punctuation and spacing dropped: "Studio 1", "STUDIO-1" and "studio1"
     * are one studio, and "Seat No." and "seat_no" are one heading. */
    private fun normalise(value: String): String {
        val out = StringBuilder(value.length)
        for (i in 0 until value.length) {
            val c = value[i]
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c))
            }
        }
        return out.toString()
    }

    // stream().limit().toList(), as the Java: unmodifiable.
    private fun first(values: Set<String>, limit: Int): List<String> =
        values.stream().limit(limit.toLong()).toList()

    // --- Lines and fields ----------------------------------------------------------------

    /** One line of the file, with the line number it came from so errors can name it. */
    private class Line(val number: Int, val fields: List<String>) {

        /** Trimmed, or null when the cell is empty — every caller wants that distinction. */
        fun at(index: Int): String? {
            if (index >= fields.size) {
                return null
            }
            val value = fields[index].javaTrim()
            return if (value.isEmpty()) null else value
        }

        fun isBlank(): Boolean = fields.stream().allMatch { it.isJavaBlank() }

        fun where(): String = "Line " + number
    }

    private fun readLines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var delimiter = 0.toChar()
        // Java's text.split("\\R", -1): with a negative limit Pattern.split keeps trailing
        // empty strings, which Kotlin's split also does, but the regex and the limit are kept
        // to the letter.
        val raw = LINE_BREAK.split(text, -1)
        for (i in raw.indices) {
            if (raw[i].isJavaBlank()) {
                continue // Excel leaves trailing empty lines, and a sheet often has a blank spacer row.
            }
            if (delimiter == 0.toChar()) {
                delimiter = sniffDelimiter(raw[i])
            }
            lines.add(Line(i + 1, split(raw[i], delimiter, i + 1)))
        }
        return lines
    }

    /**
     * Semicolon, tab, then comma — whichever the first line actually uses.
     *
     * Not a preference: Excel writes the OS list separator, which is `;` on an
     * Indonesian or European Windows, and a paste out of a spreadsheet is tab-separated. A
     * comma-only reader turns either into one column and reports "no seats", which reads as
     * our bug rather than their regional setting.
     */
    private fun sniffDelimiter(line: String): Char {
        var best = ','
        var bestCount = 0
        for (candidate in charArrayOf(';', '\t', ',')) {
            val count = countOutsideQuotes(line, candidate)
            if (count > bestCount) {
                best = candidate
                bestCount = count
            }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, candidate: Char): Int {
        var count = 0
        var quoted = false
        for (i in 0 until line.length) {
            val c = line[i]
            if (c == '"') {
                quoted = !quoted
            } else if (c == candidate && !quoted) {
                count++
            }
        }
        return count
    }

    /**
     * RFC 4180 fields on one line: `"quoted"` with `""` for a literal quote.
     *
     * A quoted field may not span lines, which real CSV allows. Refused rather than
     * half-supported: a seat label containing a newline is not a thing, and an unterminated
     * quote is far more likely to be a broken export — so it says so, naming the line.
     */
    private fun split(line: String, delimiter: Char, lineNo: Int): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else if (c == '"') {
                quoted = true
            } else if (c == delimiter) {
                fields.add(field.toString())
                field.setLength(0)
            } else {
                field.append(c)
            }
            i++
        }
        if (quoted) {
            throw IllegalArgumentException("Line " + lineNo + ": a quoted value is never closed. A seat " +
                    "label cannot span two lines, so this is usually a truncated export.")
        }
        fields.add(field.toString())
        return fields
    }
}
