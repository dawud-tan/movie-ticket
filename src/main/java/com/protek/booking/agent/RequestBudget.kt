package com.protek.booking.agent

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters
import org.jboss.logging.Logger

/**
 * Keeps each request to the model under Groq's free-tier 7,000 input tokens a minute, which one
 * request over it can never meet: a retry sends the same bytes (a benchmark request's 7,159,
 * 2026-10-29; the tool-calling benchmark is not in this repo).
 *
 * The chat memory window cannot do it, because quarkus-langchain4j 2.0.0.Beta1 commits a turn to the
 * window only when the turn ends, so every tool result of one turn reaches the model untrimmed. Here
 * a request over the limit goes out **without tools**, told by [ANSWER_NOW] to answer from what it
 * has, so the turn ends on a reply. Only if it is still over is it cut, oldest tool result first,
 * each replaced by [TRIMMED] under its own call id, so every call still has its answer. Never cut:
 * the system prompt, the customer's messages, the model's own messages, and the newest round's
 * results, which are what the model is answering from. Nothing in the chat memory changes; only what
 * this one request carries.
 *
 * Why not cut and carry on, as until 2026-10-31: a model that lost its earlier results went on
 * looking and proposed seats. Every trimmed run of two benchmark cases called `propose_seats` where
 * every untrimmed one asked which showing was meant. Without tools it cannot propose at all.
 *
 * Counted in Qwen's tokenizer ([QwenTokenCount]), which Groq's bill matches as the messages plus
 * [OVERHEAD], measured in a tool-calling benchmark against Groq's bill.
 */
object RequestBudget {
    /** Groq's 7,000 itself: the count matches its bill to the token, so any lower limit trims a
     * request Groq would take, and a trim costs answers. On 2026-10-30, under 6,500 and a count still
     * ~300 over, one case's fifth request (a real 6,306) lost the list of Bandung's two cinemas and
     * proposed seats instead of asking which cinema; 6,800 still trimmed another's 6,993. No margin is
     * left for drift in [OVERHEAD], so re-measure it whenever a tool description or the system prompt
     * changes: Groq's `inputTokens` on a run's first call, less [size] of its messages, plus 779. */
    const val LIMIT = 7_000

    /** The tool schemas and the reply prompt, measured against Groq's bill in the benchmark, whose tool
     * descriptions and system prompt are AssistantTools' and BookingAssistant's word for word. */
    const val OVERHEAD = 779

    /** The reply prompt alone (`<|im_start|>assistant\n<think>\n\n</think>\n\n`), which is what
     * [OVERHEAD] comes to once the tool schemas are left out. Read off Groq's bill: one benchmark
     * request, counted 6,537 in messages, was billed 6,544 (2026-10-31). */
    const val OVERHEAD_WITHOUT_TOOLS = 7

    const val ANSWER_NOW = "Alat tidak tersedia pada langkah ini. Jawab pelanggan sekarang dari hasil yang " +
        "sudah ada; bila masih ada yang kurang jelas, tanyakan kepada pelanggan. Jangan menulis panggilan alat."

    /** No "call it again": a stubbed request carries no tools to call. */
    const val TRIMMED = "(Hasil lama dipangkas agar permintaan muat.)"

    private val log = Logger.getLogger(RequestBudget::class.java)
    private val count = QwenTokenCount()

    fun size(messages: List<ChatMessage>): Int = OVERHEAD + count.estimateTokenCountInMessages(messages)

    fun fit(request: ChatRequest): ChatRequest {
        val before = size(request.messages())
        if (before <= LIMIT) return request
        val messages = request.messages().toMutableList()
        val system = messages.indexOfFirst { it is SystemMessage }
        if (system >= 0) messages[system] = SystemMessage.from((messages[system] as SystemMessage).text() + "\n\n" + ANSWER_NOW)
        else messages.add(0, SystemMessage.from(ANSWER_NOW))
        var size = OVERHEAD_WITHOUT_TOOLS + count.estimateTokenCountInMessages(messages)
        val newestRound = messages.indexOfLast { it is AiMessage && it.hasToolExecutionRequests() }
        for (i in 0 until newestRound) {
            if (size <= LIMIT) break
            val result = messages[i] as? ToolExecutionResultMessage ?: continue
            if (result.text() == TRIMMED) continue
            val stub = ToolExecutionResultMessage.from(result.id(), result.toolName(), TRIMMED)
            size += count.estimateTokenCountInMessage(stub) - count.estimateTokenCountInMessage(result)
            messages[i] = stub
        }
        if (size > LIMIT) {
            // Groq refuses it either way; sending it as it came keeps the refusal about the real request.
            log.warnf("request is %d tokens, still %d without tools and older results; Groq will refuse it", before, size)
            return request
        }
        log.infof("request %d tokens, over the limit: sent without tools at %d", before, size)
        return ChatRequest.builder().messages(messages)
            .parameters(DefaultChatRequestParameters.builder().overrideWith(request.parameters())
                .toolSpecifications(emptyList()).toolChoice(null).build())
            .build()
    }
}
