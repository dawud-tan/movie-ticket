package com.protek.booking.web.customer

/**
 * The two pieces of markdown the assistant's model writes and a reader cannot read raw: `**bold**`
 * and pipe tables. In production on 2026-10-17 both reached the page as literal asterisks and pipes.
 * The system prompt is not told "no markdown" because it is the tool-calling benchmark's P1 word
 * for word, and P1 is what was measured.
 *
 * Safe by order: the whole text is HTML-escaped first, exactly as Qute escapes an expression, and
 * only then are tags added, and only the ones written here. Nothing the model says becomes markup.
 * Lists and line breaks need nothing: chatTurn.html renders with `white-space: pre-line`.
 */
object ChatMarkup {
    private val BOLD = Regex("""\*\*(\S(?:.*?\S)?)\*\*""")
    private val TABLE_RULE = Regex("""\|?(\s*:?-{3,}:?\s*\|)*\s*:?-{3,}:?\s*\|?""")

    fun toHtml(text: String): String {
        val out = StringBuilder()
        val rows = mutableListOf<List<String>>()
        var header = false
        fun flushTable() {
            if (rows.isEmpty()) return
            out.append("<table>")
            rows.forEachIndexed { i, cells ->
                val tag = if (i == 0 && header) "th" else "td"
                out.append("<tr>").append(cells.joinToString("") { "<$tag>$it</$tag>" }).append("</tr>")
            }
            out.append("</table>")
            rows.clear()
            header = false
        }
        for (line in escape(text).lines()) {
            val t = line.trim()
            if (t.length > 1 && t.startsWith("|") && t.endsWith("|")) {
                if (TABLE_RULE.matches(t)) header = rows.size == 1
                else rows += t.substring(1, t.length - 1).split("|").map { it.trim() }
                continue
            }
            flushTable()
            out.append(line).append('\n')
        }
        flushTable()
        return BOLD.replace(out.toString().trimEnd('\n'), "<strong>$1</strong>")
    }

    /** Qute's own HTML escaping, character for character, so a test that asserted `&#39;` still does. */
    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
