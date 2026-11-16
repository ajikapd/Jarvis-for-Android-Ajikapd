package com.jarvis.asr

import com.jarvis.core.voice.AsrCancelReason
import com.jarvis.core.voice.AsrConfidence
import com.jarvis.core.voice.AsrErrorKind
import com.jarvis.core.voice.AsrResult
import com.jarvis.core.voice.AsrSessionConfig
import com.jarvis.core.voice.AsrSessionListener
import com.jarvis.core.voice.AsrSessionPhase
import com.jarvis.core.voice.StreamingAsrBackend
import com.jarvis.core.voice.StreamingAsrSession
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.core.voice.VoiceProviderKind
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2-08: wraps existing [AsrSession] (Sherpa) as a [StreamingAsrBackend].
 * Does not load a second model instance — caller supplies the live session factory.
 */
class LocalStreamingAsrBackend(
    private val sessionFactory: () -> AsrSession,
    override val id: String = "asr.local.sherpa",
    override val model: String = AsrModelSpec.RU_STREAMING.dirName,
    private val ready: () -> Boolean = { true },
) : StreamingAsrBackend {
    override val kind = VoiceProviderKind.LOCAL
    override val supportsStreamingPartials: Boolean = true

    override fun availability(): VoiceProviderAvailability =
        if (ready()) VoiceProviderAvailability.AVAILABLE else VoiceProviderAvailability.UNAVAILABLE

    override fun openSession(config: AsrSessionConfig, listener: AsrSessionListener): StreamingAsrSession =
        LocalStreamingSession(sessionFactory(), config, listener, id, model)
}

private class LocalStreamingSession(
    private val asr: AsrSession,
    private val config: AsrSessionConfig,
    private val listener: AsrSessionListener,
    private val backendId: String,
    private val modelId: String,
) : StreamingAsrSession {
    override val sessionId: String = config.sessionId
    override val providerId: String = backendId
    private val active = AtomicBoolean(true)
    private var lastText = ""

    override fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int) {
        if (!active.get()) return
        val result = asr.processChunk(samples)
        val text = result.text.trim()
        if (text.isNotBlank() && text != lastText) {
            lastText = text
            listener.onEvent(
                AsrResult(
                    requestId = config.requestId,
                    text = text,
                    isFinal = false,
                    confidence = AsrConfidence.Unknown,
                    language = config.language,
                    provider = backendId,
                    model = modelId,
                    sessionId = config.sessionId,
                    phase = AsrSessionPhase.PARTIAL,
                ),
            )
        }
        if (result.isEndpoint && text.isNotBlank()) {
            emitFinal(text)
        }
    }

    override fun feedPcm16le(bytes: ByteArray, sampleRateHz: Int) {
        // Convert PCM16 → float for Sherpa
        val samples = FloatArray(bytes.size / 2)
        var i = 0
        var s = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort()
            samples[s++] = v / 32768f
            i += 2
        }
        feedFloatPcm(samples.copyOf(s), sampleRateHz)
    }

    override fun finalizeUtterance() {
        if (!active.get()) return
        emitFinal(lastText)
    }

    override fun cancel(reason: AsrCancelReason) {
        if (!active.compareAndSet(true, false)) return
        asr.reset()
        listener.onEvent(
            AsrResult(
                requestId = config.requestId,
                text = lastText,
                isFinal = false,
                provider = backendId,
                model = modelId,
                sessionId = config.sessionId,
                phase = AsrSessionPhase.CANCEL,
                errorKind = AsrErrorKind.CANCELLED,
            ),
        )
    }

    override fun isActive(): Boolean = active.get()

    private fun emitFinal(text: String) {
        if (!active.compareAndSet(true, false)) return
        asr.reset()
        listener.onEvent(
            AsrResult(
                requestId = config.requestId,
                text = text,
                isFinal = true,
                confidence = AsrConfidence.Unknown,
                language = config.language,
                provider = backendId,
                model = modelId,
                sessionId = config.sessionId,
                phase = AsrSessionPhase.FINAL,
            ),
        )
    }
}
