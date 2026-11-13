package com.protek.booking.payment.snap

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins `SnapSignatures.minify` to Jackson's `readTree(body).toString()` — the rendering
 * Midtrans's own Java sample defines minify as.
 *
 * **Why this exists.** Since 2026-10-08 the payment package parses with kotlinx.serialization,
 * and minify is reproduced by `MidtransJson.render` rather than by calling Jackson. A
 * rendering that is merely equivalent JSON is not good enough: the signature covers the rendered
 * bytes, so any difference rejects every request and every notification, and presents as a
 * forgery. Jackson stays on the test classpath (Flyway and Firebase need it in production
 * anyway), which makes it the oracle here — each case is a place kotlinx's own `toString()`
 * would have differed.
 *
 * Plain JUnit, like `SnapSignaturesTest`: pure functions, nothing to boot.
 */
class MinifyMatchesJacksonTest {

    companion object {
        private val JACKSON = ObjectMapper()

        private fun matchesJackson(body: String) {
            assertEquals(JACKSON.readTree(body).toString(), SnapSignatures.minify(body),
                "minify must render exactly as Jackson does for: " + body)
        }
    }

    @Test
    fun aDecimalAmountIsRenderedAsJacksonsDoubleNotAsTyped() {
        // Jackson reads 10000.00 into a double and writes 10000.0; kotlinx keeps "10000.00".
        matchesJackson("{\"amount\": 10000.00, \"fee\": 0.10, \"rate\": 1.5e3, \"tiny\": 1E-7}")
        assertEquals("{\"amount\":10000.0}", SnapSignatures.minify("{ \"amount\" : 10000.00 }"))
    }

    @Test
    fun integersAreCanonicalDigitsIncludingMinusZeroAndBigOnes() {
        matchesJackson("{\"a\": -0, \"b\": 12345678901234567890123, \"c\": 0, \"d\": -42}")
    }

    @Test
    fun controlCharactersAreEscapedWithJacksonsUppercaseHex() {
        // \u001F is where kotlinx (lowercase hex) and Jackson (uppercase) part ways.
        matchesJackson("{\"s\": \"tab\\there\\nnew\\u0001\\u001f\\u000b end \\\"q\\\" \\\\ /\"}")
    }

    @Test
    fun nonAsciiAndEscapedUnicodeComeOutRawAsJacksonWritesThem() {
        matchesJackson("{\"name\": \"Café \\u00e9 \\u4e2d   😀\", \"emoji\": \"\\ud83d\\ude00\"}")
    }

    @Test
    fun keyOrderIsDocumentOrderAndNestingIsCompact() {
        matchesJackson("""
                {
                  "partnerReferenceNo" : "BP-1",
                  "amount" : { "value" : "50000.00", "currency" : "IDR" },
                  "list" : [ 1, [ ], { }, null, true, false, "x" ],
                  "additionalInfo" : { "acquirer" : "gopay" }
                }""".trimIndent())
    }

    @Test
    fun aDuplicateKeyKeepsItsFirstPositionAndLastValue() {
        matchesJackson("{\"a\": 1, \"b\": 2, \"a\": 3}")
    }

    @Test
    fun topLevelArraysAndScalarsRenderToo() {
        matchesJackson("[1, 2.50, \"x\"]")
        matchesJackson("\"just a string\"")
        matchesJackson("true")
    }

    @Test
    fun blankIsJavasBlank_soANonBreakingSpaceIsNotEmpty() {
        assertEquals("", SnapSignatures.minify(null))
        assertEquals("", SnapSignatures.minify(" \t\n"))
        // Kotlin's isBlank() would call this blank and hash nothing; Java's did not, and Jackson
        // refuses it as JSON, so it must still be refused.
        assertThrows(IllegalArgumentException::class.java) { SnapSignatures.minify(" ") }
    }

    @Test
    fun anUnquotedWordIsRefusedAsJacksonRefusesIt() {
        // kotlinx defers validating unquoted literals; MidtransJson validates them.
        assertThrows(IllegalArgumentException::class.java) { SnapSignatures.minify("{\"a\": abc}") }
        assertThrows(IllegalArgumentException::class.java) { SnapSignatures.minify("{\"a\": 01}") }
    }
}
