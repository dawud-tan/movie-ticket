package com.protek.booking.agent

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.GptBytePairEncodingParams
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.TokenCountEstimator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.util.Base64
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Counts in Qwen3.8-27B's own tokenizer, the tokens Groq bills against its 7,000-a-minute free tier.
 * o200k (until 2026-10-29) ran about 1.45 under it on tool results, because Qwen splits every digit:
 * one benchmark request (the benchmark is not in this repo) was 7,159 tokens from a 3,500-o200k
 * window.
 *
 * `qwen3.8-27b.tiktoken.gz` is `tokenizer.json` of Qwen/Qwen3.8-27B, revision 1d4bf0f (Apache-2.0), as
 * `base64(bytes) rank` lines: its byte-level keys mapped back to bytes. Ids follow merge order for
 * all 247,587 merges, so rank = id reproduces its BPE. The pattern and NFC are that file's
 * pre-tokenizer and normalizer.
 *
 * Copied from the tool-calling benchmark, with its rank file and its test, for [RequestBudget] (2026-10-29). The chat
 * memory window still counts in o200k ([AssistantTokenCount]); moving it here is a separate decision.
 */
class QwenTokenCount : TokenCountEstimator {
    override fun estimateTokenCountInText(text: String) = count(text)

    // <|im_start|> and <|im_end|> are one token each, and the newline after <|im_end|> one more.
    override fun estimateTokenCountInMessage(m: ChatMessage) = 3 + count(render(m))

    /** The template opens one `user` frame for a run of tool results, so each result after the first
     * in a run costs [FRAME] less than it does alone. Counting every frame put one benchmark request
     * 60 over Groq's bill (2026-10-30). */
    override fun estimateTokenCountInMessages(ms: Iterable<ChatMessage>): Int {
        var previous: ChatMessage? = null
        return ms.sumOf { m ->
            val shared = m is ToolExecutionResultMessage && previous is ToolExecutionResultMessage
            previous = m
            estimateTokenCountInMessage(m) - if (shared) FRAME else 0
        }
    }

    companion object {
        /** <|im_start|>, `user`, <|im_end|> and the newline after it. */
        const val FRAME = 4

        /** Tags the template writes that tokenizer.json lists as added tokens, which it matches whole
         * before BPE: one token each. Through BPE they are 3–4, which put the count 16 over Groq's bill
         * per tool call and its result, and 4 per assistant turn: that request, billed 6,306,
         * counted 6,610 and was trimmed under a 6,500 limit (2026-10-30). */
        private val added = Regex(listOf("<tool_call>", "</tool_call>", "<tool_response>", "</tool_response>", "<think>", "</think>")
            .joinToString("|") { Regex.escape(it) })

        private val encoding: Encoding by lazy {
            val ranks = HashMap<ByteArray, Int>(262_144)
            GZIPInputStream(QwenTokenCount::class.java.getResourceAsStream("/qwen3.8-27b.tiktoken.gz")!!)
                .bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) ranks[Base64.getDecoder().decode(line.substringBefore(' '))] = line.substringAfter(' ').toInt()
                }
            val pattern = Pattern.compile(
                """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?[\p{L}\p{M}]+|\p{N}| ?[^\s\p{L}\p{M}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+""",
                Pattern.UNICODE_CHARACTER_CLASS)
            Encodings.newLazyEncodingRegistry()
                .registerGptBytePairEncoding(GptBytePairEncodingParams("qwen3.8", pattern, ranks, emptyMap()))
                .getEncoding("qwen3.8").get()
        }

        fun count(text: String): Int {
            val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
            return added.findAll(nfc).count() + added.split(nfc).sumOf { encoding.countTokensOrdinary(it) }
        }

        /** A message as chat_template.jinja writes it between its <|im_start|> and <|im_end|>. A tool
         * result is written with its own `user` frame; [estimateTokenCountInMessages] takes back the
         * frames a run of them shares. */
        fun render(m: ChatMessage): String = when (m) {
            is SystemMessage -> "system\n" + m.text().trim()
            is UserMessage -> "user\n" + (if (m.hasSingleText()) m.singleText() else m.contents().toString()).trim()
            // preserve_thinking is unset, so the template writes a <think> block on every assistant turn
            is AiMessage -> "assistant\n<think>\n" + (m.thinking() ?: "").trim() + "\n</think>\n\n" + (m.text() ?: "").trim() +
                m.toolExecutionRequests().mapIndexed { i, call ->
                    (if (i > 0) "\n" else if ((m.text() ?: "").isNotBlank()) "\n\n" else "") +
                        "<tool_call>\n<function=${call.name()}>\n" +
                        args(call.arguments()).joinToString("") { (k, v) -> "<parameter=$k>\n$v\n</parameter>\n" } +
                        "</function>\n</tool_call>"
                }.joinToString("")
            is ToolExecutionResultMessage -> "user\n<tool_response>\n" + m.text().trim() + "\n</tool_response>"
            else -> m.toString()
        }

        private fun args(json: String?): List<Pair<String, String>> =
            if (json.isNullOrBlank()) emptyList()
            else Json.parseToJsonElement(json).jsonObject.map { (k, v) -> k to if (v is JsonPrimitive && v.isString) v.content else v.toString() }
    }
}
