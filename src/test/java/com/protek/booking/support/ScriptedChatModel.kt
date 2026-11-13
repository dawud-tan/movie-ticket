package com.protek.booking.support

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.Deque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.function.Function

/**
 * Stands in for the booking assistant's model host in every test, so `./mvnw test` never
 * calls Groq (or anything) and stays offline. The real AI service, tool loop and tools run; only
 * the model's turns are scripted. A test pushes one step per model call onto [SCRIPT]; each
 * step sees the request (the conversation so far) and returns the model's message.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class ScriptedChatModel : ChatModel {

    companion object {
        /** `@JvmField`, so `ChatAssistantTest`'s `ScriptedChatModel.SCRIPT.add(...)` is still a static field. */
        @JvmField
        val SCRIPT: Deque<Function<ChatRequest, AiMessage>> = ConcurrentLinkedDeque()
    }

    override fun doChat(request: ChatRequest): ChatResponse {
        val step = SCRIPT.poll()
        val message = if (step == null) AiMessage.from("(no scripted step left)") else step.apply(request)
        return ChatResponse.builder().aiMessage(message).build()
    }
}
