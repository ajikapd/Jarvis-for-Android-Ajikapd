package com.jarvis.core.streaming

/**
 * V2-07: adaptive sentence/phrase segmenter for LLM→TTS.
 * Does not wait for full response; flushes on punctuation or soft length.
 */
class SentenceSegmenter(
    private val softMaxChars: Int = 90,
    private val hardMaxChars: Int = 160,
    private val minFlushChars: Int = 12,
) {
    private val buffer = StringBuilder()
    private val emitted = mutableListOf<String>()

    fun push(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        return drain(final = false)
    }

    fun flush(): List<String> = drain(final = true)

    fun pending(): String = buffer.toString()

    fun allEmitted(): List<String> = emitted.toList()

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val text = buffer.toString()
            if (text.isBlank()) {
                buffer.clear()
                break
            }
            val boundary = findBoundary(text, final)
            if (boundary < 0) {
                if (final) {
                    val leftover = text.trim()
                    if (leftover.isNotEmpty()) {
                        out += leftover
                        emitted += leftover
                    }
                    buffer.clear()
                } else if (text.length >= hardMaxChars) {
                    // Force soft break at last space
                    val cut = text.lastIndexOf(' ', hardMaxChars).takeIf { it >= minFlushChars }
                        ?: hardMaxChars
                    val piece = text.substring(0, cut).trim()
                    if (piece.isNotEmpty()) {
                        out += piece
                        emitted += piece
                    }
                    buffer.delete(0, cut)
                    continue
                }
                break
            }
            val piece = text.substring(0, boundary + 1).trim()
            if (piece.length >= minFlushChars || final || looksCompleteSentence(piece)) {
                if (piece.isNotEmpty()) {
                    out += piece
                    emitted += piece
                }
                var next = boundary + 1
                while (next < text.length && text[next].isWhitespace()) next++
                buffer.delete(0, next)
            } else {
                break
            }
        }
        return out
    }

    private fun findBoundary(text: String, final: Boolean): Int {
        // Prefer sentence end
        val punct = charArrayOf('.', '!', '?', '…', '。', '！', '？')
        var best = -1
        for (i in text.indices) {
            val c = text[i]
            if (c in punct) {
                // Avoid abbrev-like single letter before '.'
                if (c == '.' && i > 0 && text[i - 1].isUpperCase() && i + 1 < text.length && text[i + 1].isLetter()) {
                    continue
                }
                best = i
                if (i + 1 >= softMaxChars) return i
            }
        }
        if (best >= minFlushChars) return best
        // Soft: comma/semicolon after enough text
        if (text.length >= softMaxChars) {
            val softMarks = charArrayOf(',', ';', ':', '—', '–')
            for (i in (softMaxChars - 1) downTo minFlushChars) {
                if (text[i] in softMarks) return i
            }
            val sp = text.lastIndexOf(' ', softMaxChars)
            if (sp >= minFlushChars) return sp
        }
        if (final && text.isNotBlank()) return text.lastIndex
        return -1
    }

    private fun looksCompleteSentence(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return t.last() in setOf('.', '!', '?', '…', '。', '！', '？')
    }
}

/**
 * Bounded queue of TTS-ready text chunks (backpressure).
 */
class BoundedTextChunkQueue(
    private val maxChunks: Int = 8,
    private val maxChars: Int = 2_400,
) {
    private val q = ArrayDeque<String>()
    private var chars = 0

    @Synchronized
    fun offer(chunk: String): Boolean {
        val t = chunk.trim()
        if (t.isEmpty()) return true
        if (q.size >= maxChunks || chars + t.length > maxChars) return false
        q.addLast(t)
        chars += t.length
        return true
    }

    @Synchronized
    fun poll(): String? {
        val t = q.removeFirstOrNull() ?: return null
        chars = (chars - t.length).coerceAtLeast(0)
        return t
    }

    @Synchronized
    fun clear() {
        q.clear()
        chars = 0
    }

    @Synchronized
    fun size(): Int = q.size

    @Synchronized
    fun isEmpty(): Boolean = q.isEmpty()
}

/**
 * V2-07 voice stream lifecycle (LLM→TTS). Extends Stage 23 StreamSession concepts
 * without a parallel StreamSessionV2.
 */
enum class VoiceStreamState {
    IDLE,
    STARTING,
    STREAMING,
    DRAINING,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    FAILED,
    FALLBACK,
}

class VoiceStreamSession(
    val requestId: String,
    val generationId: Long,
    private val streamSession: StreamSession = StreamSession(requestId),
) {
    @Volatile
    var state: VoiceStreamState = VoiceStreamState.IDLE
        private set

    val cancellation: CancellationToken get() = streamSession.cancellation
    val textQueue = BoundedTextChunkQueue()
    private val lock = Any()

    fun transition(to: VoiceStreamState): Boolean = synchronized(lock) {
        if (!isAllowed(state, to)) return false
        state = to
        when (to) {
            VoiceStreamState.CANCELLING -> {
                streamSession.emit(
                    StreamEvent(StreamEventType.CANCELLED, StreamPhase.LLM),
                )
                cancellation.cancel()
                textQueue.clear()
            }
            VoiceStreamState.CANCELLED -> {
                if (!cancellation.isCancelled()) cancellation.cancel()
                textQueue.clear()
            }
            VoiceStreamState.FAILED ->
                streamSession.emit(StreamEvent(StreamEventType.ERROR, StreamPhase.LLM))
            VoiceStreamState.COMPLETED ->
                streamSession.emit(StreamEvent(StreamEventType.FINAL_LLM, StreamPhase.LLM))
            else -> Unit
        }
        true
    }

    fun cancel() {
        transition(VoiceStreamState.CANCELLING)
        transition(VoiceStreamState.CANCELLED)
    }

    fun canSpeak(): Boolean =
        state == VoiceStreamState.STREAMING ||
            state == VoiceStreamState.DRAINING ||
            state == VoiceStreamState.STARTING

    fun underlying(): StreamSession = streamSession

    companion object {
        fun isAllowed(from: VoiceStreamState, to: VoiceStreamState): Boolean {
            if (from == to) return true
            return when (from) {
                VoiceStreamState.IDLE -> to in setOf(
                    VoiceStreamState.STARTING, VoiceStreamState.CANCELLED, VoiceStreamState.FAILED,
                )
                VoiceStreamState.STARTING -> to in setOf(
                    VoiceStreamState.STREAMING, VoiceStreamState.FALLBACK,
                    VoiceStreamState.CANCELLING, VoiceStreamState.FAILED, VoiceStreamState.CANCELLED,
                )
                VoiceStreamState.STREAMING -> to in setOf(
                    VoiceStreamState.DRAINING, VoiceStreamState.COMPLETED,
                    VoiceStreamState.CANCELLING, VoiceStreamState.FAILED, VoiceStreamState.CANCELLED,
                    VoiceStreamState.FALLBACK,
                )
                VoiceStreamState.DRAINING -> to in setOf(
                    VoiceStreamState.COMPLETED, VoiceStreamState.CANCELLING,
                    VoiceStreamState.CANCELLED, VoiceStreamState.FAILED,
                )
                VoiceStreamState.FALLBACK -> to in setOf(
                    VoiceStreamState.COMPLETED, VoiceStreamState.FAILED, VoiceStreamState.CANCELLED,
                )
                VoiceStreamState.CANCELLING -> to == VoiceStreamState.CANCELLED
                VoiceStreamState.COMPLETED,
                VoiceStreamState.CANCELLED,
                VoiceStreamState.FAILED,
                -> false
            }
        }
    }
}
