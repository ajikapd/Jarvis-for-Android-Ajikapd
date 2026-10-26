package com.jarvis.core.streaming

/**
 * Stage 23: Streaming / Low Latency contracts.
 * Interruption, cancellation, barge-in, timeout, fallback, partial results.
 */
enum class StreamPhase { ASR, LLM, TTS, IDLE }

enum class StreamEventType {
    PARTIAL_ASR,
    FINAL_ASR,
    PARTIAL_LLM,
    FINAL_LLM,
    TTS_CHUNK,
    TTS_DONE,
    BARGE_IN,
    CANCELLED,
    TIMEOUT,
    FALLBACK,
    ERROR,
}

data class StreamEvent(
    val type: StreamEventType,
    val phase: StreamPhase,
    val text: String = "",
    val atMillis: Long = 0L,
)

class CancellationToken {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    fun isCancelled(): Boolean = cancelled
    fun throwIfCancelled() {
        if (cancelled) throw StreamCancelledException()
    }
}

class StreamCancelledException : RuntimeException("stream cancelled")

data class StreamSessionConfig(
    val timeoutMs: Long = 15_000L,
    val allowBargeIn: Boolean = true,
    val allowPartialResults: Boolean = true,
)

class StreamSession(
    val sessionId: String,
    private val config: StreamSessionConfig = StreamSessionConfig(),
    private val startedAtMillis: Long = 0L,
) {
    val cancellation = CancellationToken()
    private val events = mutableListOf<StreamEvent>()
    @Volatile var phase: StreamPhase = StreamPhase.IDLE
        private set
    @Volatile var bargedIn: Boolean = false
        private set

    fun emit(event: StreamEvent) {
        cancellation.throwIfCancelled()
        if (event.type == StreamEventType.BARGE_IN) {
            if (!config.allowBargeIn) return
            bargedIn = true
            cancellation.cancel()
        }
        if (!config.allowPartialResults &&
            event.type in setOf(StreamEventType.PARTIAL_ASR, StreamEventType.PARTIAL_LLM)
        ) {
            return
        }
        phase = event.phase
        events += event
    }

    fun events(): List<StreamEvent> = events.toList()

    fun timedOut(nowMillis: Long): Boolean =
        nowMillis - startedAtMillis > config.timeoutMs

    fun markTimeout(nowMillis: Long) {
        events += StreamEvent(StreamEventType.TIMEOUT, phase, atMillis = nowMillis)
        cancellation.cancel()
    }

    fun markFallback(reason: String, nowMillis: Long) {
        events += StreamEvent(
            StreamEventType.FALLBACK,
            phase,
            text = reason.take(120),
            atMillis = nowMillis,
        )
    }
}
