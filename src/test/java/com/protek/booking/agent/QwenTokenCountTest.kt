package com.protek.booking.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** QwenTokenCount against HuggingFace `tokenizers` 0.22.2 on Qwen3.8-27B's tokenizer.json, the
 * count it has to match. A wrong pattern or byte mapping still counts, just not the same. */
class QwenTokenCountTest {

    @Test
    fun javaneseCountsAsQwensOwnTokenizerCountsIt() =
        assertEquals(22, QwenTokenCount.count("Sesuk bengi aku arep nonton film ing Rekaan Raya, kursi telu jejer."))

    @Test
    fun diacriticsEmojiAndDigitsCountAsQwensOwnTokenizerCountsThem() =
        assertEquals(56, QwenTokenCount.count("ʿAlā al-ṭāwila: kursī wāḥid, min faḍlik — 🎬 2026-10-14T19:30 Rp45.000"))

    @Test
    fun aToonShowingsTableSpendsOneTokenPerDigit() =
        assertEquals(48, QwenTokenCount.count("showings[3]{date,hallName,times}:\n  2026-10-14,Studio 1,\"10:00,12:30,19:30\""))

    @Test
    fun aToolCallsTagsCountOneTokenEach() =
        assertEquals(27, QwenTokenCount.count("<tool_call>\n<function=cinemas>\n<parameter=city>\nBandung\n</parameter>\n</function>\n</tool_call>"))

    /** The expected count is HF's on chat_template.jinja's rendering of these messages (no tools block,
     * no generation prompt: those are [RequestBudget.OVERHEAD]). Groq's bill less 779 matched that
     * rendering to the token on all 104 requests recorded on today's tool schema (2026-10-30). */
    @Test
    fun aRoundOfTwoResultsCountsAsTheChatTemplateWritesIt() {
        val calls = listOf("Bandung", "Semarang").mapIndexed { i, city ->
            ToolExecutionRequest.builder().id("c$i").name("cinemas").arguments("{\"city\":\"$city\"}").build()
        }
        val messages = listOf(
            SystemMessage.from("Kamu asisten pemesanan tiket bioskop."),
            UserMessage.from("Besok jam setengah delapan, berdua."),
            AiMessage.from(calls),
            ToolExecutionResultMessage.from(calls[0], "[2]{slug,name}:\n  merdeka-braga,Bioskop Merdeka Braga\n  layar-parahyangan,Layar Parahyangan"),
            ToolExecutionResultMessage.from(calls[1], "[1]{slug,name}:\n  sinema-simpang-lima,Sinema Simpang Lima"),
            AiMessage.from("Mau di bioskop mana?"),
        )
        assertEquals(170, QwenTokenCount().estimateTokenCountInMessages(messages))
    }
}
