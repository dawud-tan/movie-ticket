package com.protek.booking.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes a kotlinx [JsonElement] the way Jackson's generator wrote the same document: compact,
 * document order, and **Jackson's string escaping**.
 *
 * **Why this exists rather than kotlinx's own `toString()`.** The two disagree in exactly one
 * place for valid input: a control character other than the five with short forms. Jackson writes
 * U+001F as `\u001F`, kotlinx as `\u001f`. Every JSON reader decodes both to the same string, but
 * this application's JSON was Jackson's until 2026-10-08, and the owner asked that it stay
 * byte-for-byte what it was rather than be documented as different.
 * kotlinx has no setting for it — its escape table is internal — so the escaping is done
 * here, once, for every place that writes JSON:
 *  - the REST layer, through `web/RestJsonWriter.kt`;
 *  - `JsonLd`'s schema.org blocks and `WalletPass`'s JWT claims;
 *  - `payment/MidtransJson`'s re-rendering of a Midtrans body, where the bytes are signed and the
 *    case of a hex digit is the difference between a valid signature and a rejected one.
 *
 * **Literals are written as given.** A number or boolean goes out as its element's own content —
 * `5E+4`, `118`, `true` — because every producer here already hands over Jackson's form: the
 * REST serializers by construction (`JdkSerializers.kt`), `JsonLd` and `WalletPass` from the
 * value's own `toString()`, which is what Jackson's generator calls. `MidtransJson` is the one
 * caller whose literals are not in Jackson's form yet, because they come from parsed text, and it
 * canonicalises them itself before handing over strings.
 */
object JacksonText {

    private const val HEX = "0123456789ABCDEF"

    fun render(value: JsonElement): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (value.isString) quote(value.content, out) else out.append(value.content)
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((key, element) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(key, out)
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

    /** Jackson's default escaping: `"` and `\`, the five short control escapes, and `\u00XX` with
     * UPPERCASE hex for every other control character. Nothing at or above U+0020 is escaped —
     * not `<`, `&` or non-ASCII, which `JsonLd` deals with itself for its own reason. */
    fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch.code < 0x20 -> when (ch) {
                    '\b' -> out.append("\\b")
                    '\t' -> out.append("\\t")
                    '\n' -> out.append("\\n")
                    '\u000C' -> out.append("\\f")
                    '\r' -> out.append("\\r")
                    else -> out.append("\\u00").append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF])
                }
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}
