package com.jarvis.brain.aiprovider

import com.jarvis.core.aioutput.CloudToolAction
import com.jarvis.core.runtime.TtftKind
import com.jarvis.core.streaming.SentenceSegmenter
import com.jarvis.core.streaming.VoiceStreamSession
import com.jarvis.core.streaming.VoiceStreamState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * V2-07: CHAT streaming pipeline — real AI deltas → sentence chunks → sink.
 * Never executes tools. Never fakes streaming from a completed string.
 */
fun interface ChatSentenceSink {
    /** @return false to stop (cancel / barge-in) */
    fun onSentence(sentence: String, first: Boolean): Boolean
}

data class ChatStreamResult(
    val fullText: String,
    val streamed: Boolean,
    val ttftMs: Long?,
    val ttftKind: TtftKind,
    val providerId: String?,
    val modelId: String?,
    val selectionReason: String?,
    val fallbackUsed: Boolean,
    val cancelled: Boolean,
    val failed: Boolean,
    val fallbackReason: String? = null,
    val sentenceCount: Int = 0,
    val latencyMs: Long = 0L,
    val voiceState: VoiceStreamState = VoiceStreamState.COMPLETED,
)

class ChatStreamPipeline(
    private val router: AIRouter,
) {
    private val activeCancel = AtomicReference<AtomicBoolean?>(null)

    fun cancelActive() {
        activeCancel.get()?.set(true)
    }

    fun streamChat(
        profile: AiTaskProfile,
        request: AICompletionRequest,
        generationId: Long,
        isGenerationStale: () -> Boolean,
        sink: ChatSentenceSink,
    ): ChatStreamResult {
        val t0 = System.nanoTime()
        val session = VoiceStreamSession(request.requestId, generationId)
        session.transition(VoiceStreamState.STARTING)

        val cancelFlag = AtomicBoolean(false)
        activeCancel.set(cancelFlag)

        try {
            val streamProfile = profile.copy(streamingRequired = true)
            val sel = router.select(streamProfile)
            val provider = sel.provider
            if (provider == null || !provider.supportsRealStreaming()) {
                session.transition(VoiceStreamState.FALLBACK)
                return fallbackComplete(
                    profile = profile.copy(streamingRequired = false),
                    request = request,
                    session = session,
                    sel = sel,
                    reason = "no_streaming_provider",
                    t0 = t0,
                    sink = sink,
                    isGenerationStale = isGenerationStale,
                )
            }

            val streaming = provider as StreamingAIProvider
            val segmenter = SentenceSegmenter()
            var firstSentence = true
            val full = StringBuilder()
            var ttftMs: Long? = null
            var ttftKind = TtftKind.NOT_AVAILABLE
            var cancelled = false
            var failed = false
            var errorReason: String? = null
            var sentenceCount = 0
            var modelId = sel.modelId
            var pushed = 0
            val streamStart = System.nanoTime()

            fun emitSentences(parts: List<String>): Boolean {
                for (p in parts) {
                    if (cancelFlag.get() || isGenerationStale() || !session.canSpeak()) {
                        cancelled = true
                        handleCancel(session, cancelFlag)
                        return false
                    }
                    // Bound outstanding TTS text without dropping spoken order
                    if (!session.textQueue.offer(p)) {
                        session.textQueue.poll()
                        session.textQueue.offer(p)
                    }
                    sentenceCount++
                    val ok = sink.onSentence(p, firstSentence)
                    firstSentence = false
                    // dequeue shadow slot to keep bound
                    session.textQueue.poll()
                    if (!ok) {
                        cancelled = true
                        handleCancel(session, cancelFlag)
                        return false
                    }
                }
                return true
            }

            session.transition(VoiceStreamState.STREAMING)
            streaming.stream(
                request = request,
                cancelFlag = cancelFlag,
                listener = AIStreamListener { ev ->
                    if (cancelFlag.get() || isGenerationStale()) {
                        cancelled = true
                        return@AIStreamListener
                    }
                    when (ev.type) {
                        AIStreamEventType.START -> Unit
                        AIStreamEventType.TEXT -> {
                            if (ttftMs == null) {
                                ttftMs = (System.nanoTime() - streamStart) / 1_000_000
                                ttftKind = TtftKind.REAL
                            }
                            full.append(ev.text)
                            // Вызов инструмента не озвучиваем: его разберёт оркестратор.
                            val text = full.toString()
                            val safe = CloudToolAction.speakableLength(text)
                            if (safe > pushed) {
                                emitSentences(segmenter.push(text.substring(pushed, safe)))
                                pushed = safe
                            }
                        }
                        AIStreamEventType.METADATA -> {
                            ev.modelId?.let { modelId = it }
                            val m = Regex("""ttftMs=(\d+)""").find(ev.text)
                            if (m != null && ttftMs == null) {
                                ttftMs = m.groupValues[1].toLongOrNull()
                                ttftKind = TtftKind.REAL
                            }
                        }
                        AIStreamEventType.COMPLETE -> {
                            // Хвост вроде «А» оказался обычным словом, а не началом вызова.
                            val text = full.toString()
                            if (CloudToolAction.actionStart(text) < 0 && pushed < text.length) {
                                emitSentences(segmenter.push(text.substring(pushed)))
                                pushed = text.length
                            }
                            emitSentences(segmenter.flush())
                            session.transition(VoiceStreamState.DRAINING)
                            session.transition(VoiceStreamState.COMPLETED)
                        }
                        AIStreamEventType.CANCELLED -> {
                            cancelled = true
                            handleCancel(session, cancelFlag)
                        }
                        AIStreamEventType.ERROR -> {
                            failed = true
                            errorReason = ev.errorCode ?: "stream_error"
                        }
                    }
                },
            )

            if (cancelled) {
                return ChatStreamResult(
                    fullText = full.toString().trim(),
                    streamed = true,
                    ttftMs = ttftMs,
                    ttftKind = ttftKind,
                    providerId = provider.id,
                    modelId = modelId,
                    selectionReason = sel.selectionReason,
                    fallbackUsed = false,
                    cancelled = true,
                    failed = false,
                    sentenceCount = sentenceCount,
                    latencyMs = (System.nanoTime() - t0) / 1_000_000,
                    voiceState = VoiceStreamState.CANCELLED,
                )
            }

            if (failed && full.isEmpty()) {
                session.transition(VoiceStreamState.FALLBACK)
                return fallbackComplete(
                    profile = profile.copy(streamingRequired = false),
                    request = request,
                    session = session,
                    sel = sel,
                    reason = errorReason ?: "stream_failed_before_text",
                    t0 = t0,
                    sink = sink,
                    isGenerationStale = isGenerationStale,
                )
            }

            if (failed && sentenceCount > 0) {
                session.transition(VoiceStreamState.FAILED)
                return ChatStreamResult(
                    fullText = full.toString().trim(),
                    streamed = true,
                    ttftMs = ttftMs,
                    ttftKind = ttftKind,
                    providerId = provider.id,
                    modelId = modelId,
                    selectionReason = sel.selectionReason,
                    fallbackUsed = false,
                    cancelled = false,
                    failed = true,
                    fallbackReason = "partial_then_error_no_restart",
                    sentenceCount = sentenceCount,
                    latencyMs = (System.nanoTime() - t0) / 1_000_000,
                    voiceState = VoiceStreamState.FAILED,
                )
            }

            if (session.state == VoiceStreamState.STREAMING) {
                emitSentences(segmenter.flush())
                session.transition(VoiceStreamState.COMPLETED)
            }

            return ChatStreamResult(
                fullText = full.toString().trim().ifBlank {
                    segmenter.allEmitted().joinToString(" ")
                },
                streamed = true,
                ttftMs = ttftMs,
                ttftKind = ttftKind,
                providerId = provider.id,
                modelId = modelId,
                selectionReason = sel.selectionReason,
                fallbackUsed = false,
                cancelled = false,
                failed = false,
                sentenceCount = sentenceCount,
                latencyMs = (System.nanoTime() - t0) / 1_000_000,
                voiceState = session.state,
            )
        } finally {
            activeCancel.compareAndSet(cancelFlag, null)
        }
    }

    private fun handleCancel(session: VoiceStreamSession, flag: AtomicBoolean) {
        flag.set(true)
        session.cancel()
    }

    private fun fallbackComplete(
        profile: AiTaskProfile,
        request: AICompletionRequest,
        session: VoiceStreamSession,
        sel: AIProviderSelection,
        reason: String,
        t0: Long,
        sink: ChatSentenceSink,
        isGenerationStale: () -> Boolean,
    ): ChatStreamResult {
        if (isGenerationStale()) {
            session.transition(VoiceStreamState.CANCELLED)
            return ChatStreamResult(
                fullText = "",
                streamed = false,
                ttftMs = null,
                ttftKind = TtftKind.NOT_AVAILABLE,
                providerId = sel.provider?.id,
                modelId = sel.modelId,
                selectionReason = sel.selectionReason,
                fallbackUsed = false,
                cancelled = true,
                failed = false,
                fallbackReason = "stale_$reason",
                latencyMs = (System.nanoTime() - t0) / 1_000_000,
                voiceState = VoiceStreamState.CANCELLED,
            )
        }
        val (_, result) = router.complete(profile, request)
        val text = result.text?.trim().orEmpty()
        if (result.status == AICompletionStatus.SUCCESS && text.isNotBlank()) {
            // Non-stream completion: segment AFTER full text for TTS only.
            // streamed=false — not claimed as real streaming.
            val segmenter = SentenceSegmenter()
            var first = true
            var count = 0
            for (p in segmenter.push(text) + segmenter.flush()) {
                count++
                if (!sink.onSentence(p, first)) break
                first = false
            }
            session.transition(VoiceStreamState.COMPLETED)
            return ChatStreamResult(
                fullText = text,
                streamed = false,
                ttftMs = null,
                ttftKind = TtftKind.NOT_AVAILABLE,
                providerId = result.providerId,
                modelId = result.modelId,
                selectionReason = sel.selectionReason,
                fallbackUsed = true,
                cancelled = false,
                failed = false,
                fallbackReason = reason,
                sentenceCount = count,
                latencyMs = (System.nanoTime() - t0) / 1_000_000,
                voiceState = VoiceStreamState.FALLBACK,
            )
        }
        session.transition(VoiceStreamState.FAILED)
        return ChatStreamResult(
            fullText = "",
            streamed = false,
            ttftMs = null,
            ttftKind = TtftKind.NOT_AVAILABLE,
            providerId = result.providerId,
            modelId = result.modelId,
            selectionReason = sel.selectionReason,
            fallbackUsed = true,
            cancelled = false,
            failed = true,
            fallbackReason = reason,
            latencyMs = (System.nanoTime() - t0) / 1_000_000,
            voiceState = VoiceStreamState.FAILED,
        )
    }
}
