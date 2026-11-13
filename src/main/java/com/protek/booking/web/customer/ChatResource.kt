package com.protek.booking.web.customer

import com.protek.booking.agent.AssistantChat
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.security.CurrentActor
import com.protek.booking.security.Roles
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID

/**
 * `/chat`, the booking assistant's page. The assistant
 * finds the showing and proposes seats; its answer ends in a link to the existing seat map with
 * those seats ticked (`?seats=`), and the hold, the age attestation's tick and payment stay the customer's own
 * clicks there. So there is no fourth booking entry point and nothing new for the non-negotiables
 * to cover.
 *
 * **Signed in only, and capped**, because every message spends the model host's quota: an
 * anonymous chat endpoint is a cost attack. **No identity reaches the model**: it gets the text,
 * the page's language and the time, and the memory id carries an app-user id the model never sees.
 * The cap, the memory id and the model call are [AssistantChat]'s, shared with the app's
 * `POST /api/app/chat`, so a customer's day is one count across both.
 *
 * **Off until configured.** With `quarkus.langchain4j.openai.api-key` at its `__unset__` default
 * the page 404s and the nav hides the link ([com.protek.booking.agent.AssistantNav]).
 */
@Path("/chat")
@RunOnVirtualThread
@RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
class ChatResource {

    /** One exchange as the page shows it. [link] is built here, never by the model. */
    data class ChatTurn(val question: String, val answer: String, val link: String?, val seats: String?)

    @CheckedTemplate(basePath = "customer")
    object Templates {
        @JvmStatic
        external fun chat(thread: String, dailyCap: Int, host: String): TemplateInstance

        @JvmStatic
        external fun chatTurn(turn: ChatTurn): TemplateInstance
    }

    @Inject
    lateinit var chat: AssistantChat

    @Inject
    lateinit var actor: CurrentActor

    @Inject
    lateinit var language: LanguagePreference

    private fun requireConfigured() {
        if (!chat.configured()) throw NotFoundException()
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun page(): TemplateInstance {
        requireConfigured()
        return Templates.chat(UUID.randomUUID().toString(), chat.dailyCap, chat.host)
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun ask(@FormParam("message") message: String?, @FormParam("thread") thread: String?): TemplateInstance {
        requireConfigured()
        val msg = LanguagePreference.messagesIn(language.current())
        val user = actor.appUserId().orElseThrow { NotFoundException() }
        val text = message?.trim().orEmpty().take(AssistantChat.MAX_CHARS)
        return when (val reply = chat.ask(user, thread, message, language.tag())) {
            AssistantChat.Reply.Empty -> Templates.chatTurn(ChatTurn("", msg.chatEmpty(), null, null))
            AssistantChat.Reply.CapReached -> Templates.chatTurn(ChatTurn(text, msg.chatCapReached(chat.dailyCap), null, null))
            AssistantChat.Reply.Unavailable -> Templates.chatTurn(ChatTurn(text, msg.chatUnavailable(), null, null))
            is AssistantChat.Reply.Answer -> {
                val p = reply.proposal
                val link = p?.let {
                    language.path("/halls/${it.hallId}/showings/${it.sessionId}") + "?seats=" + it.seatIds.joinToString(",")
                }
                Templates.chatTurn(ChatTurn(text, reply.text, link, p?.labels?.joinToString(", ")))
            }
        }
    }

    companion object {
        const val UNSET = "__unset__"
    }
}
