package com.protek.booking.seo

import com.protek.booking.isJavaBlank
import com.protek.booking.json.JacksonText
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jboss.logging.Logger

/**
 * Serialises a schema.org document so it can sit inside
 * `<script type="application/ld+json">` without a title being able to close the script
 * element.
 *
 * **This is the JSON-safe escaper the markup needs, and the reason it
 * cannot just be Qute.** Qute HTML-escapes `{}` expressions in a `.html`
 * template, so `{film.title}` inside a script block emits `&#39;` — and the
 * content of a `<script>` element is raw text, so the browser never decodes that entity
 * and the JSON is simply broken. The obvious fix, `.raw`, is worse: staff type film
 * titles and branch names into the admin screens, so `.raw` on those values is a script
 * injection hole with a form in front of it.
 *
 * **So the escaping happens here, in code, where it can be guaranteed, and the template
 * renders the finished string with `.raw` — the same arrangement as
 * `TemplateExtensions.admissionBarcode`.** Two layers do the work:
 *
 *  - A JSON writer produces valid JSON, which is what escapes quotes and backslashes inside
 *    string values. Nothing here concatenates JSON by hand. That writer was Jackson until
 *    2026-10-08; it is now a kotlinx tree rendered by [JacksonText], which writes the bytes
 *    Jackson wrote, control-character escapes included.
 *  - `<`, `>` and `&` are then rewritten to their `\u00XX` forms.
 *    These characters never appear in JSON's own syntax, only inside string literals, and
 *    `<` is a valid JSON escape that every parser decodes back to `<`. So
 *    the document means exactly the same thing to a consumer, while the byte sequence
 *    `</script` has become unspellable no matter what an operator types.
 *
 * Escaping the characters rather than rejecting them matters: `&` is ordinary in a
 * film title ("Fast &amp; Furious"), and a markup layer that dropped such titles would be a
 * worse answer than no markup.
 */
@ApplicationScoped
class JsonLd {

    /**
     * `document` as a string ready to be dropped into a script element, or empty when it
     * could not be produced.
     *
     * Empty rather than thrown, and this is the whole failure policy of the SEO layer: a
     * page's markup is invisible decoration on a page whose job is to sell a ticket. Nothing
     * here may ever be the reason a branch page 500s.
     */
    fun render(document: Map<String, Any?>?): String {
        if (document.isNullOrEmpty()) {
            return ""
        }
        return try {
            escapeForScriptElement(JacksonText.render(toJson(document)))
        } catch (e: IllegalArgumentException) {
            LOG.warnf(e, "Could not serialise JSON-LD for %s; the page renders without it", document["@type"])
            ""
        }
    }

    /**
     * Several nodes in one script element — what a page with a list of screenings emits,
     * rather than one script element per row.
     *
     * **`@graph`, not a bare JSON array.** A bare array at the document root is
     * the obvious shape and it is subtly wrong: `@context` scopes to the node it sits
     * in, so only the first element of `[{"@context":…}, {…}]` has its terms defined and
     * every node after it is a set of undefined properties. `@graph` is the form that
     * takes one context for the whole document, and it is what Google's own multi-entity
     * examples use.
     *
     * @param nodes context-free nodes — `Builder.buildNested()` output. The context is
     *        added here precisely so a caller cannot leave one on an inner node.
     */
    fun renderGraph(nodes: List<Map<String, Any?>>?): String {
        if (nodes.isNullOrEmpty()) {
            return ""
        }
        val document = LinkedHashMap<String, Any?>()
        document["@context"] = "https://schema.org"
        if (nodes.size == 1) {
            // One node needs no graph wrapper, and a consumer reading it as a single entity is
            // the simpler thing for it to be.
            document.putAll(nodes[0])
        } else {
            document["@graph"] = nodes
        }
        return try {
            escapeForScriptElement(JacksonText.render(toJson(document)))
        } catch (e: IllegalArgumentException) {
            LOG.warn("Could not serialise JSON-LD graph; the page renders without it", e)
            ""
        }
    }

    /**
     * A document builder that drops empty values instead of emitting `null`s.
     *
     * Every optional column behind this markup is genuinely optional — `synopsis`,
     * `poster_url`, `classification`, `province`, the coordinates — and
     * schema.org's rule is that an absent property is fine while a null-valued one is an
     * error. Doing it in one place stops every call site growing its own null check.
     */
    class Builder internal constructor(type: Any) {

        private val fields = LinkedHashMap<String, Any?>()

        init {
            fields["@context"] = "https://schema.org"
            fields["@type"] = type
        }

        /** Adds `key` unless `value` is null, blank, or an empty collection. */
        fun put(key: String, value: Any?): Builder {
            when {
                value == null -> return this
                value is CharSequence && value.toString().isJavaBlank() -> return this
                value is Collection<*> && value.isEmpty() -> return this
                value is Map<*, *> && value.isEmpty() -> return this
            }
            fields[key] = value
            return this
        }

        /** The nested-object form: `"geo": {"@type": "GeoCoordinates", …}`. */
        fun build(): MutableMap<String, Any?> = fields

        /** A nested object carries no `@context` — only the outermost node does. */
        fun buildNested(): MutableMap<String, Any?> = LinkedHashMap(fields).apply { remove("@context") }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(JsonLd::class.java)

        /** Written as escapes on purpose: a raw U+2028 in a source file is legal and completely
         * invisible in a diff. (The Java version had to build these from code points, because
         * javac expands unicode escapes before lexing; Kotlin does not.) */
        private const val LINE_SEPARATOR = " "
        private const val PARAGRAPH_SEPARATOR = " "

        /**
         * A document builder that drops empty values instead of emitting `null`s — see [Builder].
         */
        @JvmStatic
        fun document(type: String): Builder = Builder(type)

        /** Same, for a type that is deliberately two types at once — see `SeoMarkup`. */
        @JvmStatic
        fun document(types: List<String>): Builder = Builder(types)

        internal fun escapeForScriptElement(json: String): String =
            json
                .replace("&", "\\u0026")
                .replace("<", "\\u003C")
                .replace(">", "\\u003E")
                // Valid inside a JSON string but a statement terminator to a JavaScript
                // parser. Nothing should be evaluating an ld+json block as script, but a
                // synopsis pasted out of a PDF is where these actually come from, and one
                // replace is cheaper than knowing every consumer.
                .replace(LINE_SEPARATOR, "\\u2028")
                .replace(PARAGRAPH_SEPARATOR, "\\u2029")

        /**
         * The value as JSON, mapped type by type the way Jackson's `writeValueAsString` mapped
         * it: a number goes out as its own `toString()` — which is what Jackson's generator
         * writes for an `Integer`, a `Double` and, unless told otherwise, a `BigDecimal` such as a
         * branch's `latitude` — maps keep their insertion order, and anything else is refused,
         * which [render] turns into "no markup" rather than a broken page.
         */
        private fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is CharSequence -> JsonPrimitive(value.toString())
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is Iterable<*> -> JsonArray(value.map { toJson(it) })
            else -> throw IllegalArgumentException("No JSON-LD form for " + value.javaClass.name)
        }
    }
}
