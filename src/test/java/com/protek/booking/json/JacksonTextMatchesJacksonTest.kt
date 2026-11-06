package com.protek.booking.json

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pins [JacksonText] to Jackson's own generator — the writer this application used for every JSON
 * byte it produced until 2026-10-08, and the one the owner asked it keep matching rather than
 * merely resembling.
 *
 * Jackson stays on the test classpath — Flyway and Firebase need it in production anyway — so it
 * is the oracle here, the same arrangement `MinifyMatchesJacksonTest` uses for the BI-SNAP
 * re-rendering. Plain JUnit: pure functions, nothing to boot.
 */
class JacksonTextMatchesJacksonTest {

    private val jackson = ObjectMapper()

    @Test
    fun everyControlCharacterIsEscapedExactlyAsJacksonEscapesIt() {
        // The case this class exists for: kotlinx writes \u001f where Jackson wrote \u001F. All 32
        // C0 controls, so the five with short forms (\b \t \n \f \r) are pinned alongside the 27
        // without.
        val controls = (0 until 0x20).map { it.toChar() }.joinToString("")
        assertEquals(jackson.writeValueAsString(controls), JacksonText.render(JsonPrimitive(controls)))
        // And the difference is real, which is the only reason JacksonText is not just toString():
        assertNotEquals(jackson.writeValueAsString("\u001F"), Json.encodeToString(JsonElement.serializer(), JsonPrimitive("\u001F")))
    }

    @Test
    fun quotesBackslashesAndEverythingAboveTheControlsMatchToo() {
        for (s in listOf(
            "plain", "", "say \"hi\"", "back\\slash", "/slash", "<script>&</script>",
            "Café é", "中文", "😀", "  ", "\u007F", "tab\there\nnew",
        )) {
            assertEquals(jackson.writeValueAsString(s), JacksonText.render(JsonPrimitive(s)), "for: $s")
        }
    }

    @Test
    fun aDocumentMatchesAsJacksonWroteTheSameValuesFromJava() {
        // The shape JsonLd and WalletPass build: insertion-ordered objects, arrays, nulls, and
        // numbers handed over as the JDK values Jackson's generator would have been given —
        // written by it with their own toString(), which is what JsonPrimitive(Number) holds.
        val java = linkedMapOf<String, Any?>(
            "@context" to "https://schema.org",
            "@type" to listOf("Event", "ScreeningEvent"),
            "screenCount" to 2,
            "iat" to 4_102_444_800L,
            "latitude" to BigDecimal("-6.200000"),
            "longitude" to BigDecimal("1E+3"),
            "ratio" to 0.1,
            "flag" to true,
            "missing" to null,
            "nested" to linkedMapOf("name" to "Studio\u001F1", "empty" to emptyList<Any>()),
        )
        assertEquals(jackson.writeValueAsString(java), JacksonText.render(tree(java)))
    }

    /** The same values as a kotlinx tree, the way JsonLd.toJson builds one. */
    private fun tree(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to tree(v) })
        is Iterable<*> -> JsonArray(value.map { tree(it) })
        else -> error("unexpected $value")
    }
}
