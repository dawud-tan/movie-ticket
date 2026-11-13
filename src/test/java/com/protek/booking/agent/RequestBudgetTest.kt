package com.protek.booking.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The tool-calling benchmark (not in this repo) runs this budget on one real request. Here three
 * rounds of one lookup each, every result 2,500 Qwen tokens (one per digit). */
class RequestBudgetTest {

    private fun round(id: String, tokens: Int = 2_500): List<ChatMessage> {
        val call = ToolExecutionRequest.builder().id(id).name("showings").arguments("{}").build()
        return listOf(AiMessage.from(call), ToolExecutionResultMessage.from(call, "1".repeat(tokens)))
    }

    private val messages = listOf(SystemMessage.from("Kamu adalah asisten."), UserMessage.from("Satu tiket buat yang jam 9 malam.")) +
        round("a") + round("b") + round("c")

    @Test
    fun stillOverWithoutTools_theOldestResultGoesFirst_andOnlyUntilTheRequestFits() {
        assertTrue(RequestBudget.size(messages) > 7_000, "${RequestBudget.size(messages)}")
        val showings = ToolSpecification.builder().name("showings").build()
        val request = RequestBudget.fit(ChatRequest.builder().messages(messages).toolSpecifications(showings).build())
        val sent = request.messages()

        assertTrue(request.toolSpecifications().isNullOrEmpty(), "no tools, so no propose_seats")
        assertTrue(RequestBudget.size(sent) <= RequestBudget.LIMIT, "${RequestBudget.size(sent)}")
        assertTrue((sent[0] as SystemMessage).text().endsWith(RequestBudget.ANSWER_NOW))
        val first = sent[3] as ToolExecutionResultMessage
        assertEquals("a", first.id())
        assertEquals(RequestBudget.TRIMMED, first.text())
        for (i in 1 until messages.size) if (i != 3) assertSame(messages[i], sent[i], "message $i")
    }

    @Test
    fun theNewestRoundIsNeverCut_evenWhenItAloneIsTooBig() {
        // Groq refuses this one; cutting what the model is answering from would only hide why.
        val request = ChatRequest.builder().messages(messages.take(2) + round("big", 7_000)).build()
        assertSame(request, RequestBudget.fit(request))
    }

    @Test
    fun aRequestUnderTheLimitIsSentAsItCame() {
        val request = ChatRequest.builder().messages(messages.take(4)).build()
        assertSame(request, RequestBudget.fit(request))
    }
}
