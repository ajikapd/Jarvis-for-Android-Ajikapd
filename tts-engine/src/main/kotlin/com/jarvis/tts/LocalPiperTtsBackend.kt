package com.jarvis.tts

import com.jarvis.core.voice.StreamingTtsBackend
import com.jarvis.core.voice.TtsAudioResult
import com.jarvis.core.voice.TtsErrorKind
import com.jarvis.core.voice.TtsVoiceConfig
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.core.voice.VoiceProviderKind

/**
 * V2-09: wraps resident [JarvisTts] (Piper/VITS) as StreamingTtsBackend.
 * Does not load a second model — caller supplies the warm engine.
 */
class LocalPiperTtsBackend(
    private val engine: () -> JarvisTts?,
    override val id: String = "tts.local.piper",
    override val model: String = "vits-piper-ru_RU-dmitri-medium",
    override val defaultVoice: String = "dmitri",
) : StreamingTtsBackend {
    override val kind = VoiceProviderKind.LOCAL
    override val supportsStreamingAudio: Boolean = false

    override fun availability(): VoiceProviderAvailability =
        if (engine() != null) VoiceProviderAvailability.AVAILABLE
        else VoiceProviderAvailability.UNAVAILABLE

    override fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        cancelFlag: () -> Boolean,
    ): TtsAudioResult {
        if (cancelFlag()) {
            return TtsAudioResult(
                samples = FloatArray(0),
                sampleRateHz = voice.sampleRateHz,
                provider = id,
                model = model,
                voice = defaultVoice,
                errorKind = TtsErrorKind.CANCELLED,
            )
        }
        val tts = engine()
            ?: return TtsAudioResult(
                samples = FloatArray(0),
                sampleRateHz = voice.sampleRateHz,
                provider = id,
                model = model,
                voice = defaultVoice,
                errorKind = TtsErrorKind.PROVIDER_UNAVAILABLE,
            )
        val t0 = System.nanoTime()
        val samples = runCatching { tts.synthesize(text) }.getOrElse {
            return TtsAudioResult(
                samples = FloatArray(0),
                sampleRateHz = tts.sampleRate,
                provider = id,
                model = model,
                voice = defaultVoice,
                errorKind = TtsErrorKind.UNKNOWN,
            )
        }
        if (cancelFlag()) {
            return TtsAudioResult(
                samples = FloatArray(0),
                sampleRateHz = tts.sampleRate,
                provider = id,
                model = model,
                voice = defaultVoice,
                errorKind = TtsErrorKind.CANCELLED,
            )
        }
        val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
        return TtsAudioResult(
            samples = samples,
            sampleRateHz = tts.sampleRate,
            provider = id,
            model = model,
            voice = defaultVoice,
            setupMs = 0L,
            synthesisMs = ms,
            streamingSupported = false,
        )
    }
}
