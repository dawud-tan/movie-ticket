package com.protek.booking.service

import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses a whole auditorium's seat layout from a short text spec.
 *
 * Why this exists at all: `HallAdminResource.addSeat` took one seat per HTTP POST,
 * which is fine for a demo hall and infeasible for a chain — a regional chain is ~3 studios per branch
 * across 38 branches, so on the order of 15,000-25,000 seats before a single ticket can be
 * sold. Onboarding was not slow, it was blocked.
 *
 * The format is one line per row, because that is the shape of the thing being described
 * and it makes every irregular case a real auditorium has fall out for free:
 *
 * ```
 *   A: 1-10
 *   B: 1-10
 *   C: 1-4, 7-12     # aisle between 4 and 7
 *   D: 1-8           # short row
 * ```
 *
 *  - **Gaps and aisles** are a gap in `sequence_no`. This is deliberate and is the
 *    whole reason the format allows several ranges per row: the schema requires `sequence_no`
 *    stay a plain integer so contiguous-block arithmetic is arithmetic, which means "seat 4
 *    and seat 7 are not neighbours" has to be expressible as a real numeric gap rather than
 *    as a flag on the seat.
 *  - **Rows of differing length** are just different ranges per line.
 *  - **Non-sequential row labels** (cinemas routinely skip "I", since it reads as "1")
 *    need no syntax: omit the line.
 *
 * Pure and dependency-free on purpose — every irregular layout above is then testable
 * without a database or a running application.
 *
 * **Java's string rules, in a parser.** Every `split` here is `Pattern.split`, which is what Java's
 * `String.split` is: a regex that drops trailing empty strings. Kotlin's `split` keeps them, and
 * `"A: 1-5,"` would then stop being accepted. `trim` is `javaTrim` and `isBlank` is
 * `isJavaBlank`, for the same reason.
 */
// An object with @JvmStatic members: Java and the tests still call
// SeatMapPlan.parse(...) statically.
object SeatMapPlan {

    /** One generated seat, before it becomes a `resource_unit` row. */
    // groupKey is nullable because `resource_unit.group_key` is, and CatalogService.seatsOf
    // copies a hall's seats into this record as the Java did; label is NOT NULL there.
    @JvmRecord
    data class Seat(val label: String, val groupKey: String?, val sequenceNo: Int,
                    /** `resource_unit.attributes.accessible`: null when the plan
                     * says nothing (a generated layout, a file without the column), which a relayout
                     * reads as "keep what the seat has". */
                    val accessible: Boolean? = null)

    /** A hall can be large but not unbounded — this catches a typo like `A: 1-100000`
     * during preview rather than as a multi-million-row insert. */
    private const val MAX_SEATS = 2000

    private val LINE_BREAK: Pattern = Pattern.compile("\\R")
    private val COMMA: Pattern = Pattern.compile(",")

    /**
     * Collects seats from either entry path — the text spec below, or [SeatMapCsv] — and
     * applies the rules both of them have to obey.
     *
     * This is what makes CSV import a second *front door* onto this generator rather
     * than a second generator. The duplicate check, the 1-based numbering rule and the per-hall
     * cap are decided in one place, so a file cannot get past a rule the textarea enforces, and
     * a rule added here cannot be added to only one of the two. That was the standing objection
     * to CSV import ("a second entry path to maintain"), and this is the answer
     * to it.
     *
     * `where` is a human location ("Line 7") used only in messages. This is a form an
     * operator fills in from a file they did not write, so naming the line is the difference
     * between a fixable error and a stack trace.
     */
    // internal where the Java was package-private: its one other caller is SeatMapCsv, now Kotlin.
    internal class Collector {

        private val seats = ArrayList<Seat>()
        /** label -> where it was first seen, so the second sighting can name the first. */
        private val byLabel = LinkedHashMap<String, String>()
        /** row and seat number -> the label already occupying that position. */
        private val byPosition = LinkedHashMap<String, String>()

        fun add(label: String?, groupKey: String?, sequenceNo: Int, where: String, accessible: Boolean? = null) {
            if (label == null || label.isJavaBlank()) {
                throw IllegalArgumentException(where + ": a seat with no label.")
            }
            if (groupKey == null || groupKey.isJavaBlank()) {
                throw IllegalArgumentException(where + ": seat " + label + " has no row.")
            }
            // Zero and negatives are rejected rather than normalised: sequence_no is load-bearing
            // for ordering and for future adjacency arithmetic, and a seat "A0" is far more likely
            // a typo than an intent.
            if (sequenceNo < 1) {
                throw IllegalArgumentException(where + ": seat numbers start at 1, got " + sequenceNo + ".")
            }

            val firstSeen = byLabel.putIfAbsent(label, where)
            if (firstSeen != null) {
                throw IllegalArgumentException(where + ": seat " + label +
                        " is listed more than once (already at " + firstSeen + ").")
            }
            // Two *different* labels in one position of one row is the spreadsheet-shaped version
            // of the same mistake — a column inserted halfway down a file, typically. The seat map
            // orders by (group_key, sequence_no), so these two would render on top of each other.
            val occupant = byPosition.putIfAbsent(groupKey + "\u0000" + sequenceNo, label)
            if (occupant != null) {
                throw IllegalArgumentException(where + ": seats " + occupant + " and " + label +
                        " are both seat " + sequenceNo + " of row " + groupKey + ".")
            }

            seats.add(Seat(label, groupKey, sequenceNo, accessible))
        }

        fun isEmpty(): Boolean = seats.isEmpty()

        /** @throws IllegalArgumentException if the plan is over the per-hall cap. */
        fun finish(): List<Seat> {
            if (seats.size > MAX_SEATS) {
                throw IllegalArgumentException(
                    "That describes " + seats.size + " seats, which is over the " + MAX_SEATS +
                            "-seat limit for a single hall. Check for a typo in a range, or for a " +
                            "file that covers more than one studio.")
            }
            // java.util.List.copyOf, as the Java returned: unmodifiable.
            return java.util.List.copyOf(seats)
        }
    }

    /**
     * @throws IllegalArgumentException with a message naming the offending line, so the admin
     *         UI can show the operator what to fix rather than a stack trace.
     */
    @JvmStatic
    fun parse(spec: String?): List<Seat> {
        if (spec == null || spec.isJavaBlank()) {
            throw IllegalArgumentException("Seat plan is empty — describe at least one row, e.g. \"A: 1-10\".")
        }

        // The collector detects a row/seat named twice across the *whole* plan, not just within
        // one line: "A: 1-5" followed later by "A: 5-8" is a duplicate seat A5, and silently
        // inserting it would produce two identically-labelled seats that the map renders as one.
        val seats = Collector()

        val lines = LINE_BREAK.split(spec)
        for (i in lines.indices) {
            val line = stripComment(lines[i]).javaTrim()
            if (line.isEmpty()) {
                continue
            }
            val lineNo = i + 1

            val colon = line.indexOf(':')
            if (colon < 0) {
                throw IllegalArgumentException(
                    "Line " + lineNo + " (\"" + line + "\"): expected \"<row>: <seats>\", e.g. \"A: 1-10\".")
            }
            val groupKey = line.substring(0, colon).javaTrim()
            if (groupKey.isEmpty()) {
                throw IllegalArgumentException("Line " + lineNo + ": row label is missing before the colon.")
            }
            val ranges = line.substring(colon + 1).javaTrim()
            if (ranges.isEmpty()) {
                throw IllegalArgumentException(
                    "Line " + lineNo + " (row " + groupKey + "): no seat numbers given after the colon.")
            }

            for (part in COMMA.split(ranges)) {
                parseRange(part.javaTrim(), groupKey, lineNo, seats)
            }
        }

        if (seats.isEmpty()) {
            throw IllegalArgumentException("Seat plan describes no seats.")
        }
        return seats.finish()
    }

    private fun parseRange(part: String, groupKey: String, lineNo: Int, seats: Collector) {
        val where = "Line " + lineNo + " (row " + groupKey + ")"
        if (part.isEmpty()) {
            throw IllegalArgumentException(where + ": empty entry — a stray or trailing comma.")
        }

        val from: Int
        val to: Int
        val dash = part.indexOf('-')
        if (dash < 0) {
            from = parseNumber(part, where)
            to = from
        } else {
            from = parseNumber(part.substring(0, dash).javaTrim(), where)
            to = parseNumber(part.substring(dash + 1).javaTrim(), where)
        }

        if (to < from) {
            throw IllegalArgumentException(where + ": range \"" + part +
                    "\" counts backwards — write it as " + to + "-" + from + ".")
        }

        for (n in from..to) {
            seats.add(groupKey + n, groupKey, n, where)
        }
    }

    private fun parseNumber(text: String, where: String): Int {
        try {
            return Integer.parseInt(text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException(where + ": \"" + text + "\" is not a seat number.")
        }
    }

    /** Trailing `#` comments, so a saved plan can carry a note about why a row has a gap. */
    private fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash < 0) line else line.substring(0, hash)
    }

    /**
     * Builds a spec for the common regular case, so the quick form and the advanced textarea
     * share one code path rather than two generators that can disagree.
     *
     * `rowsExpr` is either a letter range (`A-K`) or an explicit comma list
     * (`A,B,C,E`). Ranges are single letters only — a range across multi-character
     * labels has no well-defined meaning, and the explicit list covers that case anyway.
     * Note that a range does *not* skip "I": guessing at cinema convention would be
     * magic, and the generated spec is editable before it is committed.
     */
    @JvmStatic
    fun uniform(rowsExpr: String?, seatsPerRow: Int): String {
        if (rowsExpr == null || rowsExpr.isJavaBlank()) {
            throw IllegalArgumentException("Give the rows to generate, e.g. \"A-K\" or \"A,B,C\".")
        }
        if (seatsPerRow < 1) {
            throw IllegalArgumentException("Seats per row must be at least 1, got " + seatsPerRow + ".")
        }

        val spec = StringBuilder()
        for (row in expandRows(rowsExpr.javaTrim())) {
            spec.append(row).append(": 1-").append(seatsPerRow).append('\n')
        }
        return spec.toString()
    }

    private fun expandRows(rowsExpr: String): List<String> {
        val rows = ArrayList<String>()
        if (rowsExpr.contains(",")) {
            for (row in COMMA.split(rowsExpr)) {
                val trimmed = row.javaTrim()
                if (!trimmed.isEmpty()) {
                    rows.add(trimmed)
                }
            }
            if (rows.isEmpty()) {
                throw IllegalArgumentException("No row labels found in \"" + rowsExpr + "\".")
            }
            return rows
        }

        val dash = rowsExpr.indexOf('-')
        if (dash < 0) {
            rows.add(rowsExpr)
            return rows
        }

        // The default locale, as the Java's toUpperCase() used; Kotlin's uppercase() would be
        // Locale.ROOT.
        val fromText = rowsExpr.substring(0, dash).javaTrim().uppercase(Locale.getDefault())
        val toText = rowsExpr.substring(dash + 1).javaTrim().uppercase(Locale.getDefault())
        if (fromText.length != 1 || toText.length != 1
            || !Character.isLetter(fromText[0]) || !Character.isLetter(toText[0])) {
            throw IllegalArgumentException("Row range \"" + rowsExpr +
                    "\" must run between single letters, e.g. \"A-K\". For anything else list the rows: \"A,B,AA\".")
        }
        val from = fromText[0]
        val to = toText[0]
        if (to < from) {
            throw IllegalArgumentException("Row range \"" + rowsExpr + "\" counts backwards.")
        }
        for (row in from..to) {
            rows.add(row.toString())
        }
        return rows
    }
}
