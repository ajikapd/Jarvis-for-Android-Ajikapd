package com.jarvis.brain.aiprovider

import com.jarvis.core.runtime.TtftKind

/**
 * V2-07: typed AI streaming contract (provider-agnostic).
 * Not fake post-hoc string splitting.
 */
enum class AIStreamEventType {
    START,
    TEXT,
    METADATA,
    ERROR,
    COMPLETE,
    CANCELLED,
}

data class AIStreamEvent(
    val type: AIStreamEventType,
    val text: String = "",
    val requestId: String = "",
    val providerId: String = "",
    val modelId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val atElapsedMs: Long = 0L,
)

fun interface AIStreamListener {
    fun onEvent(event: AIStreamEvent)
}

/**
 * Cancelable handle for an in-flight stream.
 */
interface AIStreamHandle {
    val requestId: String
    fun cancel()
    fun isCancelled(): Boolean
}

data class AIStreamSummary(
    val requestId: String,
    val providerId: String,
    val modelId: String?,
    val status: AICompletionStatus,
    val fullText: String,
    val latencyMs: Long,
    val ttftMs: Long?,
    val ttftKind: TtftKind,
    val streamed: Boolean,
    val cancelled: Boolean,
    val error: AIProviderError? = null,
    val chunkCount: Int = 0,
)

/**
 * Default: providers that do not support streaming return CANCELLED/ERROR via listener.
 */
interface StreamingAIProvider : AIProvider {
    fun stream(
        request: AICompletionRequest,
        listener: AIStreamListener,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(request.cancelRequested),
    ): AIStreamHandle
}

/** Adapter: non-streaming providers expose [stream] as failure without fake chunks. */
fun AIProvider.asStreamingOrNull(): StreamingAIProvider? = this as? StreamingAIProvider

fun AIProvider.supportsRealStreaming(): Boolean =
    capabilities.supportsStreaming && this is StreamingAIProvider
