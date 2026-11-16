package com.jarvis.core.runtime

/**
 * V2-06: optional immediate acknowledgement for slow paths.
 * Never claims action success. Not used for FAST_SKILL / TEMPLATE.
 */
object ImmediateAckPolicy {

    private val slowChatHints = listOf(
        "объясни", "расскажи", "подумай", "сравни", "почему", "как работает",
        "подробно", "разверн", "анализ",
    )

    /**
     * @return short ack phrase, or null if ack should be skipped.
     */
    fun acknowledgementFor(
        route: RouteDecision,
        userText: String,
        estimatedAiMs: Long? = null,
    ): String? {
        when (route) {
            RouteDecision.FAST_SKILL,
            RouteDecision.TEMPLATE,
            RouteDecision.CLARIFY,
            -> return null
            else -> Unit
        }
        val t = userText.lowercase().trim()
        if (t.length < 24 && estimatedAiMs == null) return null
        val looksHeavy = slowChatHints.any { t.contains(it) } || t.length >= 80 ||
            (estimatedAiMs != null && estimatedAiMs >= 2500L)
        if (!looksHeavy) return null
        return when (route) {
            RouteDecision.CHAT, RouteDecision.PLAN, RouteDecision.UNKNOWN -> "Секунду…"
            else -> null
        }
    }
}

/**
 * V2-06: stream-ready capability flags (prep for V2-07, no fake streaming).
 */
data class StreamReadiness(
    val asrPartialSupported: Boolean = true,
    val llmStreamingSupported: Boolean = false,
    val ttsChunkSupported: Boolean = false,
    val cancellationSupported: Boolean = true,
    val bargeInSupported: Boolean = true,
) {
    companion object {
        fun forProviders(llmStreaming: Boolean, ttsChunk: Boolean = false): StreamReadiness =
            StreamReadiness(
                llmStreamingSupported = llmStreaming,
                ttsChunkSupported = ttsChunk,
            )
    }
}

/**
 * Generation/cancellation gate for overlapping voice turns.
 */
class InteractionGenerationGate {
    @Volatile private var generation: Long = 0L
    @Volatile private var cancelFlag: Boolean = false

    fun begin(): Long {
        cancelFlag = false
        return ++generation
    }

    fun current(): Long = generation

    fun cancelCurrent() {
        cancelFlag = true
        generation++
    }

    fun isStale(gen: Long): Boolean = gen != generation || cancelFlag

    fun isCancelled(): Boolean = cancelFlag
}
