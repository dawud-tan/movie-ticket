package com.protek.booking.admission

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * What can actually be proven about a hand-written barcode encoder without a scanner in the room.
 *
 * Two independent halves, and the split is the point. The **checksum** is arithmetic and is
 * checked against values worked by hand below, so an error in the encoding logic fails here. The
 * **pattern table** cannot be verified that way — nothing in this repository, and nothing
 * inside this suite, can decode a symbol — so it is instead checked against the structural
 * invariants ISO/IEC 15417 imposes on every Code 128 character: six elements totalling eleven
 * modules, an even number of bar modules,
 * and no two characters sharing a pattern. A transposed or mistyped digit breaks at least one of
 * those with high probability, which is the whole reason to assert them rather than eyeball 107
 * six-digit strings.
 *
 * **What these do not prove:** that anything *else* decodes the output. These
 * assertions and the encoder read the same table, so a green suite means this repository agrees
 * with itself. On 2026-08-31 that gap was checked outside the suite: a script rendered the real
 * SVG in Chrome and handed it to ZXing, which shares no code and no table with anything here, and
 * it read the token back. That check is not in `./mvnw test` on purpose — it needs Chrome and a
 * decoder fetched into a scratch directory, and the build is meant to need neither.
 *
 * Still not proven by any of it: that a reader at a real door decodes it off a real screen.
 * That needs hardware, in the same class as the seat map's "check in a real
 * browser" note. Nothing here should be read as standing in for it.
 */
class Code128Test {

    @Test
    fun everyPatternIsAWellFormedCode128Character() {
        assertEquals(107, Code128.patternCount(),
            "103 data values, three start characters and stop — a short table means a " +
                "character silently missing, not a smaller barcode")

        for (value in 0 until Code128.patternCount()) {
            val pattern = Code128.pattern(value)
            val isStop = value == 106
            val expectedElements = if (isStop) 7 else 6
            val expectedModules = if (isStop) 13 else 11

            assertEquals(expectedElements, pattern.length, "element count of symbol " + value)

            var total = 0
            var barModules = 0
            for (i in 0 until pattern.length) {
                val width = pattern[i] - '0'
                assertTrue(width >= 1 && width <= 4,
                    "element widths run 1-4 modules; symbol " + value + " has " + width)
                total += width
                if (i % 2 == 0) {
                    barModules += width
                }
            }
            assertEquals(expectedModules, total, "module count of symbol " + value)
            // The symbology's defining parity property: bars always total an even number of
            // modules. It is what lets a scanner tell a symbol read forwards from one read
            // backwards, and it catches a single mistyped digit that happens to preserve the sum.
            assertEquals(0, barModules % 2,
                "bar modules of symbol " + value + " must be even, got " + barModules)
        }
    }

    @Test
    fun noTwoCharactersShareAPattern() {
        // A duplicate is the one transcription error the per-symbol invariants above cannot see:
        // both entries would be individually well-formed, and one of the two characters would
        // decode as the other.
        val seen = HashSet<String>()
        for (value in 0 until Code128.patternCount()) {
            assertTrue(seen.add(Code128.pattern(value)),
                "symbol " + value + " repeats the pattern " + Code128.pattern(value))
        }
    }

    @Test
    fun theChecksumMatchesValuesWorkedByHand() {
        // "A" — Start B is 104, 'A' is 65 - 32 = 33 in code set B, and the start character carries
        // weight 1: (104 + 1*33) mod 103 = 137 mod 103 = 34.
        assertArrayEquals(intArrayOf(104, 33, 34, 106), Code128.symbols("A"))

        // "AB" — (104 + 1*33 + 2*34) mod 103 = 205 mod 103 = 102. Two characters, because a
        // weight applied as a constant rather than as position passes the single-character case.
        assertArrayEquals(intArrayOf(104, 33, 34, 102, 106), Code128.symbols("AB"))

        // A space is symbol value 0, so the sum is the start value alone: 104 mod 103 = 1. This is
        // the case where an off-by-one in the character-to-value mapping shows up as a checksum
        // that is not 1.
        assertArrayEquals(intArrayOf(104, 0, 1, 106), Code128.symbols(" "))
    }

    @Test
    fun theSymbolStreamIsStartThenDataThenChecksumThenStop() {
        val token = "BPT-0123456789ABCDEF01234567"
        val symbols = Code128.symbols(token)

        assertEquals(token.length + 3, symbols.size)
        assertEquals(104, symbols[0], "Start B")
        assertEquals(106, symbols[symbols.size - 1], "Stop")
        assertTrue(symbols[symbols.size - 2] >= 0 && symbols[symbols.size - 2] < 103,
            "the check character is a modulo-103 residue")
        // .code: Java's 'B' - 32 is an int; Kotlin's would be the Char '"'.
        assertEquals('B'.code - 32, symbols[1], "first data character")
    }

    @Test
    fun aTokenEncodesToTheModuleCountTheSymbologyPredicts() {
        // Start + data + check are 11 modules each; stop is 13. Getting this wrong by a single
        // module means every bar after that point is drawn in the wrong place.
        val token = "BPT-0123456789ABCDEF01234567"
        val modules = Code128.modules(token)
        assertEquals(11 * (token.length + 2) + 13, modules.size)
    }

    @Test
    fun theSymbolOpensAndClosesOnABar() {
        // Both start and stop patterns begin with a bar, and stop ends on one. A symbol that
        // opened on a space would mean the alternation had drifted, and the quiet zone would be
        // silently one element wider than it looks.
        val modules = Code128.modules("BPT-DEADBEEF")
        assertTrue(modules[0], "first module is a bar")
        assertTrue(modules[modules.size - 1], "last module is a bar")
    }

    @Test
    fun barsAndSpacesAlternateInRunsOfAtMostFourModules() {
        // The alternation is positional in the encoder rather than stored per run, so this is
        // what would break if a pattern of odd element count ever landed anywhere but last.
        val modules = Code128.modules("BPT-DEADBEEFCAFE01234567")
        var run = 1
        for (i in 1 until modules.size) {
            run = if (modules[i] == modules[i - 1]) run + 1 else 1
            assertTrue(run <= 4, "run of " + run + " identical modules ending at index " + i)
        }
    }

    @Test
    fun aCharacterOutsideCodeSetBIsRefusedRatherThanDropped() {
        // Dropping it would print one token under a barcode encoding a different one, and the
        // discovery would be a customer at a door.
        val e = assertThrows(IllegalArgumentException::class.java) { Code128.symbols("BPT-CAFÉ") }
        assertTrue(e.message!!.contains("printable ASCII"), e.message)

        assertThrows(IllegalArgumentException::class.java) { Code128.symbols("") }
    }

    @Test
    fun theSvgCarriesTheQuietZoneAndDrawsOneRectPerBarRun() {
        val token = "BPT-DEADBEEF"
        val modules = Code128.modules(token)
        val svg = Code128.toSvg(token)

        // 10 modules of margin either side. A scanner needs it to find the symbol's edges, so it
        // is drawn in rather than left to whatever CSS the page happens to have.
        assertTrue(svg.contains("viewBox=\"0 0 " + (modules.size + 20) + " 60\""), svg)

        var barRuns = 0
        for (i in modules.indices) {
            if (modules[i] && (i == 0 || !modules[i - 1])) {
                barRuns++
            }
        }
        // One background rect plus one per bar run — merged runs, not one rect per module, which
        // on a page rendering a barcode per seat is the difference that matters.
        assertEquals(barRuns + 1, Pattern.compile("<rect").split(svg, -1).size - 1)
        assertTrue(svg.contains("aria-label=\"Admission code " + token + "\""), svg)
    }

    @Test
    fun theAriaLabelIsEscapedSoAValueCannotBreakOutOfTheAttribute() {
        // V1 mints [A-Z0-9-] only, so this cannot happen today. It is asserted because an SVG
        // attribute is the wrong place to discover that the token format changed.
        val svg = Code128.toSvg("A\"B&C<D")
        assertTrue(svg.contains("aria-label=\"Admission code A&quot;B&amp;C&lt;D\""), svg)
    }
}
