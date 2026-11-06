package com.protek.booking.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The cinema's templates hold nothing its Content-Security-Policy refuses (CanonicalAuthorityFilter's
 * POLICY): no inline script, no `style=` attribute, no `hx-on` and no `hx-trigger` filter (htmx evals
 * both: the seat map's `[document.visibilityState === 'visible']` was one) and no `on*=` handler.
 *
 * A policy violation fails nothing at all: Report-Only only logs it in the visitor's console, and
 * enforced it silently drops the script or the style. So this is the only place a template edit that
 * brings one back gets caught. Plain JUnit, no Quarkus: it reads the files as they are on disk.
 */
class CspReadyTemplatesTest {

    private val templates = Path.of("src/main/resources/templates")

    /** The cinema's templates: the ones its resources render, whichever directory they sit in. */
    private val cinema = listOf("customer", "admin", "push").map { templates.resolve(it) } +
        listOf(templates.resolve("base.html"), templates.resolve("seoHead.html"))

    private val refused = mapOf(
        "inline <script>" to Regex("<script(?![^>]*\\bsrc=)(?![^>]*application/ld\\+json)[^>]*>"),
        "style attribute" to Regex("\\sstyle\\s*="),
        "hx-on attribute" to Regex("\\shx-on"),
        "hx-trigger filter" to Regex("hx-trigger\\s*=\\s*\"[^\"]*\\["),
        "on* handler" to Regex("\\son[a-z]+\\s*="),
        "javascript: URL" to Regex("javascript:", RegexOption.IGNORE_CASE),
    )

    @Test
    fun noCinemaTemplateCarriesWhatTheContentSecurityPolicyRefuses() {
        val files = cinema.flatMap { root ->
            Files.walk(root).use { walk -> walk.filter { it.isRegularFile() && it.extension == "html" }.toList() }
        }
        // A wrong path would make every check below pass on nothing.
        assertTrue(files.size > 30, "found only ${files.size} templates under $templates")
        val hits = files.flatMap { file ->
            // Qute comments stripped first: several explain the very constructs refused here.
            val markup = file.readText().replace(Regex("(?s)\\{!.*?!\\}"), "")
            refused.flatMap { (what, pattern) -> pattern.findAll(markup).map { "$file: $what: ${it.value}" }.toList() }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
