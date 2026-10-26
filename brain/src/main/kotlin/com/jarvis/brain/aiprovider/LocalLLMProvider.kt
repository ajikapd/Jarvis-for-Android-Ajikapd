package com.jarvis.brain.aiprovider

import com.jarvis.brain.ChatLlmEngine

/**
 * Stage 10: Local LLM provider — делегирует существующему [ChatLlmEngine]
 * (например LlamaChatLlmEngine). Не трогает wake/ASR.
 */
class LocalLLMProvider(
    private val engine: ChatLlmEngine,
    override val id: String = ID,
    override val displayName: String = "Local LLM",
    private val maxContextTokens: Int = 256,
    private val maxOutputTokens: Int = 64,
) : AIProvider {

    override val kind: AIProviderKind = AIProviderKind.LOCAL

    override val capabilities: AIProviderCapabilities = AIProviderCapabilities(
        supportsStreaming = false,
        supportsCancellation = false,
        supportsStructuredDecide = true,
        maxContextTokens = maxContextTokens,
        maxOutputTokens = maxOutputTokens,
        offlineCapable = true,
        requiresNetwork = false,
        defaultModelId = modelIdHint(),
        qualityTier = QualityRequirement.FAST,
        speedTier = QualityRequirement.FAST,
        privacyClass = PrivacyLevel.LOCAL_REQUIRED,
        notes = listOf("backed_by=ChatLlmEngine", "privacy=local"),
    )

    private fun modelIdHint(): String {
        val named = (engine as? ModelNamed)?.modelId
        if (!named.isNullOrBlank()) return named
        return "local.gguf"
    }

    override fun availability(): AIProviderAvailability = when {
        !engine.isReady() -> AIProviderAvailability.UNAVAILABLE
        engine.isLoaded() -> AIProviderAvailability.AVAILABLE
        else -> AIProviderAvailability.DEGRADED // ready on disk, not warmed
    }

    override fun warm() {
        if (engine.isReady()) engine.warm()
    }

    override fun complete(request: AICompletionRequest): AICompletionResult {
        if (request.cancelRequested) {
            return AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.CANCELLED,
                error = AIProviderError(
                    AIProviderErrorCategory.CANCELLED,
                    "CANCELLED",
                    "Cancellation requested before generation",
                ),
            )
        }
        if (!engine.isReady()) {
            return AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.FAILURE,
                error = AIProviderError(
                    AIProviderErrorCategory.NOT_READY,
                    "MODEL_NOT_READY",
                    "Local model file not available",
                ),
            )
        }
        val started = System.nanoTime()
        return try {
            val text = if (request.wantStructured) {
                engine.decide(
                    request.userText,
                    request.history,
                    request.memories,
                    request.persona,
                )?.utterance?.takeIf { it.isNotBlank() }
                    ?: engine.generate(
                        request.userText,
                        request.history,
                        request.memories,
                        request.persona,
                    )
            } else {
                engine.generate(
                    request.userText,
                    request.history,
                    request.memories,
                    request.persona,
                )
            }
            val latency = (System.nanoTime() - started) / 1_000_000
            if (text.isNullOrBlank()) {
                AICompletionResult(
                    requestId = request.requestId,
                    providerId = id,
                    status = AICompletionStatus.FAILURE,
                    latencyMs = latency,
                    error = AIProviderError(
                        AIProviderErrorCategory.GENERATION_FAILED,
                        "EMPTY_OUTPUT",
                        "Local LLM returned empty output",
                    ),
                )
            } else {
                AICompletionResult(
                    requestId = request.requestId,
                    providerId = id,
                    status = AICompletionStatus.SUCCESS,
                    text = text,
                    latencyMs = latency,
                    modelId = modelIdHint(),
                    streamed = false,
                    ttftMs = null,
                    ttftKind = com.jarvis.core.runtime.TtftKind.NOT_AVAILABLE,
                )
            }
        } catch (t: Throwable) {
            AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.FAILURE,
                latencyMs = (System.nanoTime() - started) / 1_000_000,
                error = AIProviderError(
                    AIProviderErrorCategory.INTERNAL,
                    "LOCAL_EXCEPTION",
                    t.message?.take(200) ?: t.javaClass.simpleName,
                ),
            )
        }
    }

    companion object {
        const val ID = "local.llm"
    }
}

/** Optional model id exposure for local engines (e.g. GGUF filename). */
interface ModelNamed {
    val modelId: String
}
