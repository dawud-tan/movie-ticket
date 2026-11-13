package com.protek.booking.web.customer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The box's 2026-10-17 transcripts, reduced: bold and a pipe table become markup, and nothing the
 * model writes does. */
class ChatMarkupTest {

    @Test
    fun boldBecomesStrongAndListsStayTextForPreLine() {
        assertEquals("- <strong>Film:</strong> Avenger dooms day (SU)\n- <strong>Kursi:</strong> D2 &amp; D3",
            ChatMarkup.toHtml("- **Film:** Avenger dooms day (SU)\n- **Kursi:** D2 & D3"))
    }

    @Test
    fun aPipeTableBecomesATableWithItsHeaderRow() {
        assertEquals("Jadwal besok:\n<table><tr><th>Studio</th><th>Mulai</th></tr>" +
            "<tr><td>Studio 1</td><td>19:00 WIB</td></tr></table>Mau saya usulkan kursi?",
            ChatMarkup.toHtml("Jadwal besok:\n| Studio | Mulai |\n|--------|:-----:|\n| Studio 1 | 19:00 WIB |\nMau saya usulkan kursi?"))
    }

    @Test
    fun whatTheModelWritesIsEscapedBeforeAnyTagIsAdded() {
        assertEquals("<strong>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</strong>\n" +
            "<table><tr><td>&lt;img src=x onerror=alert(1)&gt;</td></tr></table>",
            ChatMarkup.toHtml("**<script>alert('x')</script>**\n| <img src=x onerror=alert(1)> |"))
    }

    @Test
    fun loneAsterisksAndPipesInProseAreLeftAlone() {
        assertEquals("2 * 3 ** 4 dan a | b", ChatMarkup.toHtml("2 * 3 ** 4 dan a | b"))
    }
}
