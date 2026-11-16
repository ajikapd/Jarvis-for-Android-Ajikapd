package com.jarvis.brain.aiprovider

import com.jarvis.brain.ChatLlmEngine

/**
 * Chat LLM: Hybrid AIRouter выбирает Cloud (Nara) или Local по [AiTaskProfile].
 * Telemetry: lastProvider / lastModel / lastSelectionReason / lastFallback.
 */
class RoutedChatLlmEngine(
    private val localProvider: () -> ChatLlmEngine?,
    private val routerProvider: () -> AIRouter?,
    private val isOnline: () -> Boolean,
    private val preferCloud: () -> Boolean,
    private val complexity: () -> AIRequestComplexity = { AIRequestComplexity.SIMPLE },
    private val privacyLevel: () -> PrivacyLevel = { PrivacyLevel.NORMAL },
) : ChatLlmEngine {

    @Volatile var lastProviderId: String? = null
        private set
    @Volatile var lastModelId: String? = null
        private set
    @Volatile var lastSelectionReason: String? = null
        private set
    @Volatile var lastFallback: Boolean = false
        private set
    @Volatile var lastLatencyMs: Long = 0L
        private set
    @Volatile var lastTtftMs: Long? = null
        private set
    @Volatile var lastTtftKind: com.jarvis.core.runtime.TtftKind =
        com.jarvis.core.runtime.TtftKind.NOT_AVAILABLE
        private set
    @Volatile var lastStreamed: Boolean = false
        private set

    override fun isReady(): Boolean {
        val local = localProvider()
        if (local?.isReady() == true) return true
        val router = routerProvider() ?: return false
        val sel = router.select(
            AiTaskProfile.forChat(
                isOnline = isOnline(),
                preferCloud = preferCloud(),
                complexity = complexity(),
                privacy = privacyLevel(),
            ),
        )
        return sel.provider != null &&
            sel.provider.availability() != AIProviderAvailability.UNAVAILABLE
    }

    override fun isLoaded(): Boolean = localProvider()?.isLoaded() == true || isReady()

    override fun warm() {
        localProvider()?.warm()
    }

    override fun generate(
        userText: String,
        history: List<String>,
        memories: String,
        persona: String,
        assembled: com.jarvis.core.llm.AssembledLlmContext?,
    ): String? {
        val router = routerProvider()
        val requestId = "chat-${System.nanoTime()}"
        val profile = AiTaskProfile.forChat(
            isOnline = isOnline(),
            preferCloud = preferCloud(),
            complexity = complexity(),
            privacy = privacyLevel(),
            requestId = requestId,
            userTextLen = userText.length,
        )
        if (router != null) {
            val (sel, result) = router.complete(
                profile,
                AICompletionRequest(
                    requestId = requestId,
                    userText = userText,
                    history = history,
                    memories = memories,
                    persona = persona,
                    assembledContext = assembled,
                ),
            )
            remember(sel, result)
            if (result.status == AICompletionStatus.SUCCESS && !result.text.isNullOrBlank()) {
                android.util.Log.i(
                    "Jarvis.CloudLLM",
                    "ok provider=${sel.provider?.id} model=${sel.modelId} " +
                        "reason=${sel.selectionReason} fallback=${sel.fallbackUsed} " +
                        "latency=${result.latencyMs}ms",
                )
                return result.text
            }
            android.util.Log.w(
                "Jarvis.CloudLLM",
                "router miss status=${result.status} err=${result.error?.code} " +
                    "provider=${sel.provider?.id} — try direct local",
            )
        }
        // Last-resort local without router (router missing / all failed with no local)
        val local = localProvider()?.generate(userText, history, memories, persona, assembled)
        if (!local.isNullOrBlank()) {
            lastProviderId = LocalLLMProvider.ID
            lastModelId = (localProvider() as? ModelNamed)?.modelId ?: "local"
            lastSelectionReason = "DIRECT_LOCAL"
            lastFallback = true
        }
        return local
    }

    private fun remember(sel: AIProviderSelection, result: AICompletionResult) {
        lastProviderId = sel.provider?.id ?: result.providerId
        lastModelId = result.modelId ?: sel.modelId
        lastSelectionReason = sel.selectionReason
        lastFallback = sel.fallbackUsed
        lastLatencyMs = result.latencyMs
        lastTtftMs = result.ttftMs
        lastTtftKind = result.ttftKind
        lastStreamed = result.streamed
    }
}
