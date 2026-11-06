package com.protek.booking.agent

import com.protek.booking.web.customer.ChatResource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One message to the booking assistant, for both of its doors: `/chat` (`ChatResource.ask`) and
 * the app's `POST /api/app/chat` (`AppApiResource.chat`). Both come here so the daily cap is one
 * count per app user, whichever door a message used, and so the memory id, the thread parsing and
 * the model's failure handling cannot drift apart.
 *
 * The caller passes the verified app-user id; nothing here reads an identity from the request. The
 * memory id is `{appUserId}:{thread}`, so a thread id copied from another account addresses a
 * different conversation, not that account's.
 */
@ApplicationScoped
class AssistantChat {

    sealed interface Reply {
        data class Answer(val text: String, val proposal: Proposal?) : Reply
        data object Empty : Reply
        data object CapReached : Reply
        data object Unavailable : Reply
    }

    @Inject
    lateinit var assistant: BookingAssistant

    @Inject
    lateinit var proposals: ProposalHolder

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    lateinit var apiKey: String

    /** Named in the privacy notice; it must say where the text actually goes. */
    @ConfigProperty(name = "booking.assistant.host")
    lateinit var host: String

    @ConfigProperty(name = "booking.assistant.daily-messages", defaultValue = "30")
    var dailyCap: Int = 30

    /** shortcut: per-JVM, so a restart resets everyone's count and two instances would each
     * allow the cap; fine for one server, move to the database before scaling out. */
    private val used = ConcurrentHashMap<UUID, Pair<LocalDate, Int>>()

    fun configured(): Boolean = apiKey != ChatResource.UNSET

    fun ask(user: UUID, thread: String?, message: String?, languageTag: String): Reply {
        val text = message?.trim().orEmpty().take(MAX_CHARS)
        if (text.isEmpty()) return Reply.Empty
        if (!spend(user)) return Reply.CapReached
        // The thread is caller input; parsing it keeps the memory id to `{uuid}:{uuid}`.
        val threadId = runCatching { UUID.fromString(thread) }.getOrElse { UUID.randomUUID() }
        val answer = try {
            assistant.chat("$user:$threadId", BookingAssistant.turn(ZonedDateTime.now(WIB), languageTag, text))
        } catch (e: RuntimeException) {
            // A model host that is busy, rate-limited or down is a sentence, never a broken page.
            LOG.warnf("booking assistant failed: %s", e.toString())
            return Reply.Unavailable
        }
        return Reply.Answer(answer, proposals.proposal)
    }

    private fun spend(user: UUID): Boolean {
        val today = LocalDate.now(WIB)
        var allowed = false
        used.compute(user) { _, prev ->
            val count = if (prev == null || prev.first != today) 0 else prev.second
            allowed = count < dailyCap
            today to (if (allowed) count + 1 else count)
        }
        return allowed
    }

    companion object {
        const val MAX_CHARS = 500
        private val WIB: ZoneId = ZoneId.of("Asia/Jakarta")
        private val LOG: Logger = Logger.getLogger(AssistantChat::class.java)
    }
}
