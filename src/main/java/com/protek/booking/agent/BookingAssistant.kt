package com.protek.booking.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.TokenCountEstimator
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.Capability
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatModelName
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator
import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage
import io.quarkiverse.langchain4j.RegisterAiService
import io.quarkus.arc.Arc
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.function.Function
import java.util.function.Supplier

/**
 * The booking assistant: quarkus-langchain4j's AI
 * service with `@Tool`, in the JVM — the loop a tool-calling benchmark measured, chosen over the
 * LangGraph sidecar first planned. The system prompt is the benchmark's P1 word for word,
 * because that is the prompt `qwen3.8-27b` was measured on; editing it means re-measuring.
 *
 * Application-scoped so the chat memory outlives one request: a conversation is several POSTs.
 * The memory id is `{appUserId}:{thread}`, built by [AssistantChat], so one account cannot address
 * another's thread. shortcut: memory is in the JVM and ends with the process; move it to a store
 * if a restart mid-conversation starts to matter.
 */
@ApplicationScoped
@RegisterAiService(
    tools = [AssistantTools::class],
    chatLanguageModelSupplier = BudgetedModel::class,
    toolHallucinationStrategy = UnknownToolAnswer::class,
    maxSequentialToolInvocations = 12,
)
interface BookingAssistant {

    @SystemMessage(
        """
Kamu adalah asisten pemesanan tiket bioskop. Kamu membantu pelanggan menemukan kota, bioskop,
film, jadwal tayang, dan kursi kosong dengan memakai alat yang tersedia.

Aturan:
- Kamu hanya bisa MENGUSULKAN kursi dengan alat propose_seats. Pelanggan sendiri yang meninjau
  dan mengonfirmasi kursi itu di peta kursi. Jangan pernah mengatakan bahwa kursi sudah ditahan,
  dipesan, dikonfirmasi, atau dibayar.
- Tidak ada alat untuk menahan, membatalkan, mengonfirmasi, atau membayar. Jika pelanggan
  memintanya, katakan bahwa kamu tidak bisa, dan jelaskan bahwa pelanggan menyelesaikannya
  sendiri di peta kursi.
- Ambil setiap ID (siteRef, filmRef, hallId, sessionId) dan label kursi hanya dari hasil alat.
  Jangan mengarang ID atau kursi.
- Tanggal dan jam ditafsirkan di zona waktu bioskop (WIB, Asia/Jakarta). Waktu sekarang
  disertakan di setiap pesan pelanggan. Jam ditulis 24 jam (19:30).
- Usulkan kursi hanya jika pelanggan jelas meminta kursi untuk satu pertunjukan tertentu.
  Jika film, bioskop atau kota, tanggal, jam, atau jumlah kursi belum jelas, tanyakan dulu.
- Jangan mengusulkan kursi jika pelanggan hanya bertanya, berandai-andai, atau meminta agar
  jangan dipesan dulu.
- Balas dalam bahasa yang dipakai pelanggan."""
    )
    fun chat(@MemoryId memoryId: String, @UserMessage message: String): String

    companion object {
        /** The user turn as the tool-calling benchmark (not in this repo) sent it: today's time
         * goes here, never in the system prompt, where it would stop prompt caching. Keys are
         * English so the wrapper does not hint at a reply language. */
        fun turn(now: ZonedDateTime, pageLang: String, text: String): String {
            val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            return "[now=${now.withNano(0).toOffsetDateTime()} ($day); page_lang=$pageLang]\n$text"
        }
    }
}

/** The token count `quarkus.langchain4j.chat-memory.type=token-window` sizes the conversation by.
 * The extension asks the application for this bean and the OpenAI extension supplies none.
 * jtokkit has no Qwen tokenizer, and `OpenAiTokenCountEstimator` refuses `qwen/qwen3.8-27b` as an
 * unknown model, so this counts in gpt-4o's o200k_base. On the benchmark's transcripts Groq
 * billed 1.17–1.33× this count (1.30 overall, 59 cases, 2026-10-23); `application.properties`
 * sizes the budget with that ratio. */
@ApplicationScoped
class AssistantTokenCount {
    @Produces
    @Singleton
    fun estimator(): TokenCountEstimator = OpenAiTokenCountEstimator(OpenAiChatModelName.GPT_4_O)
}

/** The extension's model, held here because once the AI service takes [BudgetedModel] nothing else
 * injects it, and an uninjected bean is removed. In tests it is `ScriptedChatModel`. */
@ApplicationScoped
@Unremovable
class AssistantModel {
    @Inject
    lateinit var model: ChatModel
}

/** [AssistantModel]'s model with each request cut to Groq's 7,000 a minute by [RequestBudget], as
 * the benchmark's harness cuts its own. Each call goes to that model's `chat`, so its retries
 * and listeners still run. */
class BudgetedModel : Supplier<ChatModel> {
    override fun get(): ChatModel = object : ChatModel {
        private fun target() = Arc.container().instance(AssistantModel::class.java).get().model
        override fun chat(chatRequest: ChatRequest): ChatResponse = target().chat(RequestBudget.fit(chatRequest))
        override fun defaultRequestParameters(): ChatRequestParameters = target().defaultRequestParameters()
        override fun supportedCapabilities(): Set<Capability> = target().supportedCapabilities()
        override fun provider() = target().provider()
    }
}

/** A tool name the model made up gets an answer it can recover from, not an exception that ends
 * the reply. */
@ApplicationScoped
class UnknownToolAnswer : Function<ToolExecutionRequest, ToolExecutionResultMessage> {
    override fun apply(request: ToolExecutionRequest): ToolExecutionResultMessage =
        ToolExecutionResultMessage.from(request, "Error: tidak ada alat bernama ${request.name()}.")
}
