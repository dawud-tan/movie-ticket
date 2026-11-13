package com.protek.booking.payment

import com.protek.booking.isJavaBlank
import com.protek.booking.json.JacksonText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger

/**
 * How this codebase reads and re-renders Midtrans's JSON, on kotlinx.serialization — and why the
 * rendering copies Jackson's byte for byte rather than using kotlinx's own `toString()`.
 *
 * **The signature is over a re-rendering, so the re-rendering is part of the protocol.** BI-SNAP's
 * `minify(body)` is defined by Midtrans's own Java sample as Jackson's
 * `new ObjectMapper().readTree(body).toString()` (`SnapSignatures.minify`). Our
 * signature over a request and Midtrans's signature over a notification are both computed on
 * that rendering, so it has to match Jackson's exactly, not merely be equivalent JSON. kotlinx
 * renders the same tree differently in three places, each of which would change a digest and
 * present as "signature rejected":
 *  - **decimal numbers** — Jackson reads `10000.00` into a `double` and writes `10000.0`;
 *    kotlinx keeps the literal `10000.00`;
 *  - **integers** — Jackson writes canonical digits (`-0` becomes `0`); kotlinx keeps the literal;
 *  - **control-character escapes** — Jackson writes `\u001F`, kotlinx `\u001f` (the escaping
 *    itself is shared with the REST layer, in `json/JacksonText`).
 *
 * So [render] reproduces Jackson's tree output, and [text] reproduces `JsonNode.asText()` (the
 * same number rule), which is how every field was read before the swap. `MinifyMatchesJacksonTest`
 * pins both against Jackson itself, which stays on the test classpath for exactly that purpose.
 *
 * **Where the two still differ, and why it does not matter:** only on input that is not valid
 * JSON. Jackson ignores trailing garbage after the first value and rejects raw control characters
 * inside a string; kotlinx does the reverse on both. A real Midtrans body is valid JSON, and for an
 * attacker-supplied one the verdict is the same either way — the digest cannot match a signature
 * they cannot produce.
 */
internal object MidtransJson {

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** Strict JSON parse. Throws (an `IllegalArgumentException`) on anything that is not JSON. */
    fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    /**
     * A response body, parsed. An empty or blank body is [JsonNull] rather than an error, because
     * that is what Jackson's `readTree` did (it returned a "missing" node): every field then reads as
     * null and the caller's own response-code check decides. Throwing here instead would turn an
     * empty 200 from Midtrans's cancel into a failure the Jackson version never raised.
     */
    fun parseResponse(body: String?): JsonElement =
        if (body == null || body.isJavaBlank()) JsonNull else parse(body)

    /**
     * A notification body as the two web endpoints read it (`web/payment/`), which until
     * 2026-10-08 was Jackson's `readTree`: blank is [JsonNull] by the same rule as [parseResponse]
     * — every field reads as null and the signature check refuses it, as before — and anything
     * that is not JSON throws, which the endpoints answer with a 400.
     *
     * "Not JSON" has to include an unquoted word in a field nobody reads. kotlinx defers checking
     * those to whoever reads the field, so `{"order_id":"BP-1","junk":abc}` would otherwise be
     * accepted where Jackson's parser refused the whole body. Rendering the tree once validates
     * every literal in it, through the same rule [render] already applies.
     */
    fun parseBody(body: String?): JsonElement = parseResponse(body).also { render(it) }

    /** Jackson's `text(node, field)` idiom: the field's text, or null when the node is absent, is
     * not an object, lacks the field, or holds JSON `null`. */
    fun text(node: JsonElement?, field: String): String? {
        val value = (node as? JsonObject)?.get(field) ?: return null
        return if (value is JsonNull) null else asText(value)
    }

    /** Jackson's `JsonNode.asText()`: a string's own content, a number in Jackson's canonical form,
     * `true`/`false`, and the empty string for an object or array. */
    fun asText(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) value.content else literal(value.content).text
        else -> ""
    }

    /** Jackson's `readTree(json).toString()` — compact, document order, Jackson's escaping. */
    fun render(value: JsonElement): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonPrimitive ->
                if (value.isString) JacksonText.quote(value.content, out)
                else literal(value.content).let { if (it.quoted) JacksonText.quote(it.text, out) else out.append(it.text) }
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((key, element) in value) {
                    if (!first) out.append(',')
                    first = false
                    JacksonText.quote(key, out)
                    out.append(':')
                    write(element, out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { i, element ->
                    if (i > 0) out.append(',')
                    write(element, out)
                }
                out.append(']')
            }
        }
    }

    private class Literal(val text: String, val quoted: Boolean)

    /**
     * An unquoted literal as Jackson's tree holds and writes it. kotlinx defers validating these, so
     * this is also where non-JSON such as `{"a": abc}` is refused, as Jackson's parser refuses it.
     */
    private fun literal(content: String): Literal {
        if (content == "true" || content == "false") return Literal(content, quoted = false)
        if (!JSON_NUMBER.matches(content)) {
            throw IllegalArgumentException("Not a JSON value: $content")
        }
        val integral = content.none { it == '.' || it == 'e' || it == 'E' }
        if (integral) {
            // Jackson parses these into an int, long or BigInteger and writes the digits back.
            return Literal(BigInteger(content).toString(), quoted = false)
        }
        // Jackson's default tree holds every fraction or exponent as a double and writes it with
        // Double.toString. Overflow becomes Infinity, which Jackson's generator quotes by default.
        val d = content.toDouble()
        return Literal(d.toString(), quoted = d.isInfinite())
    }
}
