package com.jarvis.brain.aiprovider

import com.jarvis.core.runtime.LatencyClock
import com.jarvis.core.runtime.TtftKind
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloud LLM через OpenAI-compatible API.
 * V2-07: real SSE streaming when [client] supports stream transport.
 */
class CloudLLMProvider(
    override val id: String = ID,
    override val displayName: String = "Cloud LLM (OpenAI-compatible)",
    private val client: OpenAiCompatibleCloudClient? = null,
    private val modelProvider: () -> String = { NaraRouterDefaults.MODEL_FAST },
    private val systemPromptProvider: () -> String = {
        "Ты Джарвис — голосовой ассистент. Отвечай кратко по-русски, без markdown и списков, 1–3 предложения."
    },
    private val maxContextTokens: Int = 8_192,
    private val maxOutputTokens: Int = NaraRouterDefaults.MAX_OUTPUT_TOKENS,
    private val streamingEnabled: Boolean = true,
    /** Legacy test hooks when [client] is null. */
    private val endpointConfigured: Boolean = false,
    private val apiKeyPresent: Boolean = false,
) : StreamingAIProvider {

    override val kind: AIProviderKind = AIProviderKind.CLOUD

    override val capabilities: AIProviderCapabilities = AIProviderCapabilities(
        supportsStreaming = streamingEnabled && client != null,
        supportsCancellation = true,
        supportsStructuredDecide = true,
        maxContextTokens = maxContextTokens,
        maxOutputTokens = maxOutputTokens,
        offlineCapable = false,
        requiresNetwork = true,
        defaultModelId = NaraRouterDefaults.MODEL_FAST,
        qualityTier = QualityRequirement.QUALITY,
        speedTier = QualityRequirement.FAST,
        privacyClass = PrivacyLevel.NORMAL,
        notes = listOf(
            "provider=openai_compatible",
            "openai_compatible=true",
            "endpoint=user_configured",
            "streaming=${streamingEnabled && client != null}",
        ),
    )

    override fun availability(): AIProviderAvailability {
        return if (client != null && client.isConfigured()) {
            AIProviderAvailability.AVAILABLE
        } else {
            AIProviderAvailability.UNAVAILABLE
        }
    }

    override fun warm() = Unit

    fun activeModelId(preferred: String? = null): String =
        preferred?.takeIf { it.isNotBlank() }
            ?: modelProvider().ifBlank { NaraRouterDefaults.MODEL_FAST }

    private fun resolveSystem(request: AICompletionRequest): String {
        val assembled = request.assembledContext
        return assembled?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(systemPromptProvider())
                if (request.persona.isNotBlank()) {
                    append(' ')
                    append(
                        when (request.persona.uppercase()) {
                            "FRIENDLY" ->
                                "Режим FRIENDLY: тепло, на «ты», живые короткие фразы, лёгкий юмор, без канцелярита."
                            "MINIMAL" ->
                                "Режим MINIMAL: максимум одно короткое предложение, только суть, без воды."
                            "DEVELOPER" ->
                                "Режим DEVELOPER: технично, точно, термины допустимы, без сюсюканья."
                            else ->
                                "Режим CLASSIC: спокойный вежливый дворецкий, чёткие сдержанные ответы."
                        },
                    )
                }
                if (request.memories.isNotBlank()) {
                    append(" Память: ").append(request.memories.take(400))
                }
                if (request.wantStructured || request.taskType == AiTaskType.SEMANTIC_INTERPRETATION) {
                    append(" Отвечай строго в запрошенном структурированном формате, без лишнего текста.")
                }
            }
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
                    "Cancellation requested",
                ),
            )
        }
        val c = client
        if (c == null || !c.isConfigured()) {
            return AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.FAILURE,
                error = AIProviderError(
                    AIProviderErrorCategory.UNAVAILABLE,
                    "CLOUD_NO_KEY",
                    "Cloud API key not configured",
                ),
            )
        }

        val started = System.nanoTime()
        val model = activeModelId(request.preferredModelId)
        val system = resolveSystem(request)
        val history = request.assembledContext?.historyLines ?: request.history
        val result = c.chatCompletion(
            model = model,
            systemPrompt = system,
            userText = request.userText,
            history = history,
            maxTokens = maxOutputTokens,
        )
        val latency = (System.nanoTime() - started) / 1_000_000
        return result.fold(
            onSuccess = { text ->
                AICompletionResult(
                    requestId = request.requestId,
                    providerId = id,
                    status = AICompletionStatus.SUCCESS,
                    text = text.take(800),
                    latencyMs = latency,
                    modelId = model,
                    streamed = false,
                    ttftMs = null,
                    ttftKind = TtftKind.NOT_AVAILABLE,
                )
            },
            onFailure = { err -> mapFailure(request.requestId, model, latency, err) },
        )
    }

    override fun stream(
        request: AICompletionRequest,
        listener: AIStreamListener,
        cancelFlag: AtomicBoolean,
    ): AIStreamHandle {
        if (request.cancelRequested) cancelFlag.set(true)
        val cancelled = cancelFlag
        val handle = object : AIStreamHandle {
            override val requestId: String = request.requestId
            override fun cancel() {
                cancelled.set(true)
            }
            override fun isCancelled(): Boolean = cancelled.get()
        }
        if (!capabilities.supportsStreaming || client == null || !client.isConfigured()) {
            listener.onEvent(
                AIStreamEvent(
                    type = AIStreamEventType.ERROR,
                    requestId = request.requestId,
                    providerId = id,
                    errorCode = "STREAM_UNSUPPORTED",
                    errorMessage = "Streaming not available",
                    atElapsedMs = LatencyClock.elapsedMs(),
                ),
            )
            return handle
        }
        if (cancelled.get()) {
            listener.onEvent(
                AIStreamEvent(
                    type = AIStreamEventType.CANCELLED,
                    requestId = request.requestId,
                    providerId = id,
                    atElapsedMs = LatencyClock.elapsedMs(),
                ),
            )
            return handle
        }

        val model = activeModelId(request.preferredModelId)
        val system = resolveSystem(request)
        val history = request.assembledContext?.historyLines ?: request.history
        val started = System.nanoTime()
        var firstTokenAt: Long? = null
        var chunkCount = 0

        listener.onEvent(
            AIStreamEvent(
                type = AIStreamEventType.START,
                requestId = request.requestId,
                providerId = id,
                modelId = model,
                atElapsedMs = LatencyClock.elapsedMs(),
            ),
        )

        val result = client.chatCompletionStream(
            model = model,
            systemPrompt = system,
            userText = request.userText,
            history = history,
            maxTokens = maxOutputTokens,
            isCancelled = { cancelled.get() || request.cancelRequested },
            onDelta = { delta ->
                if (cancelled.get()) return@chatCompletionStream
                if (firstTokenAt == null) firstTokenAt = System.nanoTime()
                chunkCount++
                listener.onEvent(
                    AIStreamEvent(
                        type = AIStreamEventType.TEXT,
                        text = delta,
                        requestId = request.requestId,
                        providerId = id,
                        modelId = model,
                        atElapsedMs = LatencyClock.elapsedMs(),
                    ),
                )
            },
        )

        val latency = (System.nanoTime() - started) / 1_000_000
        if (cancelled.get()) {
            listener.onEvent(
                AIStreamEvent(
                    type = AIStreamEventType.CANCELLED,
                    requestId = request.requestId,
                    providerId = id,
                    modelId = model,
                    atElapsedMs = LatencyClock.elapsedMs(),
                ),
            )
            return handle
        }

        result.fold(
            onSuccess = { text ->
                val ttft = firstTokenAt?.let { (it - started) / 1_000_000 }
                listener.onEvent(
                    AIStreamEvent(
                        type = AIStreamEventType.COMPLETE,
                        text = text.take(800),
                        requestId = request.requestId,
                        providerId = id,
                        modelId = model,
                        atElapsedMs = LatencyClock.elapsedMs(),
                    ),
                )
                // Metadata for callers that only listen to events
                listener.onEvent(
                    AIStreamEvent(
                        type = AIStreamEventType.METADATA,
                        text = "ttftMs=${ttft ?: -1};chunks=$chunkCount;latencyMs=$latency",
                        requestId = request.requestId,
                        providerId = id,
                        modelId = model,
                        atElapsedMs = LatencyClock.elapsedMs(),
                    ),
                )
            },
            onFailure = { err ->
                val msg = err.message.orEmpty()
                if (msg.contains("CANCELLED")) {
                    listener.onEvent(
                        AIStreamEvent(
                            type = AIStreamEventType.CANCELLED,
                            requestId = request.requestId,
                            providerId = id,
                            modelId = model,
                            atElapsedMs = LatencyClock.elapsedMs(),
                        ),
                    )
                } else {
                    listener.onEvent(
                        AIStreamEvent(
                            type = AIStreamEventType.ERROR,
                            requestId = request.requestId,
                            providerId = id,
                            modelId = model,
                            errorCode = "CLOUD_STREAM_ERROR",
                            errorMessage = msg.take(200),
                            atElapsedMs = LatencyClock.elapsedMs(),
                        ),
                    )
                }
            },
        )
        return handle
    }

    private fun mapFailure(
        requestId: String,
        model: String,
        latency: Long,
        err: Throwable,
    ): AICompletionResult {
        val msg = err.message?.take(200) ?: err.javaClass.simpleName
        val category = when {
            msg.contains("HTTP 401") || msg.contains("HTTP 403") ->
                AIProviderErrorCategory.UNAVAILABLE
            msg.contains("HTTP 429") ->
                AIProviderErrorCategory.TIMEOUT
            msg.contains("HTTP") || msg.contains("Unable to resolve") ||
                msg.contains("failed to connect", ignoreCase = true) ->
                AIProviderErrorCategory.NETWORK
            else -> AIProviderErrorCategory.GENERATION_FAILED
        }
        return AICompletionResult(
            requestId = requestId,
            providerId = id,
            status = AICompletionStatus.FAILURE,
            latencyMs = latency,
            modelId = model,
            error = AIProviderError(category, "CLOUD_ERROR", msg),
        )
    }

    companion object {
        const val ID = "cloud.llm"
    }
}
