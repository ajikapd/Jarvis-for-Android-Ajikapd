package com.jarvis.brain.aiprovider

import com.jarvis.brain.ChatLlmEngine
import com.jarvis.brain.LlmStructuredDecision

/**
 * Адаптер: [AIProvider] → [ChatLlmEngine] для существующих потребителей
 * (DialogueResponder и т.п.) без ломки API.
 */
class ChatLlmEngineFromProvider(
    private val provider: AIProvider,
) : ChatLlmEngine {

    override fun isReady(): Boolean =
        provider.availability() != AIProviderAvailability.UNAVAILABLE

    override fun isLoaded(): Boolean =
        provider.availability() == AIProviderAvailability.AVAILABLE

    override fun warm() {
        provider.warm()
    }

    override fun generate(
        userText: String,
        history: List<String>,
        memories: String,
        persona: String,
        assembled: com.jarvis.core.llm.AssembledLlmContext?,
    ): String? {
        val result = provider.complete(
            AICompletionRequest(
                requestId = "chat-${System.nanoTime()}",
                userText = userText,
                history = history,
                memories = memories,
                persona = persona,
                wantStructured = false,
                assembledContext = assembled,
            ),
        )
        return if (result.status == AICompletionStatus.SUCCESS) result.text else null
    }

    override fun decide(
        userText: String,
        history: List<String>,
        memories: String,
        persona: String,
    ): LlmStructuredDecision? {
        if (!provider.capabilities.supportsStructuredDecide) {
            return super.decide(userText, history, memories, persona)
        }
        val result = provider.complete(
            AICompletionRequest(
                requestId = "decide-${System.nanoTime()}",
                userText = userText,
                history = history,
                memories = memories,
                persona = persona,
                wantStructured = true,
            ),
        )
        if (result.status != AICompletionStatus.SUCCESS) return null
        val text = result.text ?: return null
        return com.jarvis.brain.LlmDecisionParser.parse(text)
            ?: LlmStructuredDecision(
                kind = com.jarvis.brain.LlmDecisionKind.ANSWER,
                utterance = text.take(400),
                raw = text,
            )
    }
}
