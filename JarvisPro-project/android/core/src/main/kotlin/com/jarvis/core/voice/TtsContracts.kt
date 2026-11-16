package com.jarvis.core.voice

/**
 * V2-09: provider-neutral streaming TTS contracts.
 * Separate domain from AIRouter and StreamingAsrRouter.
 */

enum class TtsPreference {
    AUTO,
    CLOUD_PREFERRED,
    LOCAL_ONLY,
}

enum class TtsRouteReason {
    OFFLINE,
    PRIVACY,
    LOCAL_ONLY,
    USER_PREFERENCE,
    CLOUD_UNAVAILABLE,
    LOCAL_UNAVAILABLE,
    ONLINE_LATENCY,
    ONLINE_QUALITY,
    FALLBACK,
    COOLDOWN,
    UNSUPPORTED_VOICE,
}

enum class TtsErrorKind {
    NETWORK,
    AUTH,
    RATE_LIMIT,
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    UNSUPPORTED_VOICE,
    UNSUPPORTED_FORMAT,
    DECODING,
    PLAYBACK,
    CANCELLED,
    EMPTY_TEXT,
    UNKNOWN,
}

enum class TtsSessionState {
    IDLE,
    STARTING,
    SYNTHESIZING,
    BUFFERING,
    PLAYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class TtsVoiceConfig(
    val language: String = "ru-RU",
    val voice: String = "default",
    val model: String = "",
    val speakingRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val sampleRateHz: Int = 22_050,
    val channels: Int = 1,
)

data class TtsRouteContext(
    val isOnline: Boolean,
    val preference: TtsPreference = TtsPreference.AUTO,
    val privacyRestricted: Boolean = false,
    val cloudHealthy: Boolean = true,
    val localHealthy: Boolean = true,
    val preferLowLatency: Boolean = true,
    val language: String = "ru",
)

data class TtsRouteDecision(
    val providerId: String,
    val kind: VoiceProviderKind,
    val model: String,
    val voice: String,
    val reason: TtsRouteReason,
    val streamingSupported: Boolean,
)

/** Float PCM mono result from any backend (single conversion boundary upstream). */
data class TtsAudioResult(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val provider: String,
    val model: String,
    val voice: String,
    val setupMs: Long = 0L,
    val synthesisMs: Long = 0L,
    val streamingSupported: Boolean = false,
    val fallback: Boolean = false,
    val fallbackReason: TtsRouteReason? = null,
    val errorKind: TtsErrorKind? = null,
) {
    val isEmpty: Boolean get() = samples.isEmpty()
}

data class TtsTelemetry(
    var ttsProvider: String? = null,
    var ttsModel: String? = null,
    var ttsVoice: String? = null,
    var ttsRoute: String? = null,
    var ttsRouteReason: String? = null,
    var ttsFallback: Boolean = false,
    var ttsStreaming: Boolean = false,
    var ttsSessionId: String? = null,
    var ttsSetupMs: Long? = null,
    var ttsFirstAudioMs: Long? = null,
    var ttsFirstPlaybackMs: Long? = null,
    var ttsSynthesisMs: Long? = null,
    var ttsPlaybackMs: Long? = null,
    var ttsTotalMs: Long? = null,
    var ttsQueueDepth: Int? = null,
    var ttsCancelled: Boolean = false,
    var ttsErrorCategory: String? = null,
) {
    fun summary(): String =
        "tts provider=${ttsProvider ?: "-"} model=${ttsModel ?: "-"} voice=${ttsVoice ?: "-"} " +
            "route=${ttsRoute ?: "-"} reason=${ttsRouteReason ?: "-"} " +
            "fb=$ttsFallback stream=$ttsStreaming " +
            "setup=${ttsSetupMs ?: "-"} firstAudio=${ttsFirstAudioMs ?: "-"} " +
            "playback=${ttsFirstPlaybackMs ?: "-"} synth=${ttsSynthesisMs ?: "-"} " +
            "total=${ttsTotalMs ?: "-"} q=${ttsQueueDepth ?: "-"} " +
            "cancel=$ttsCancelled err=${ttsErrorCategory ?: "-"}"
}

/**
 * Streaming-capable TTS backend: synthesizes one utterance (sentence chunk) to PCM.
 * True audio streaming from provider is optional; [supportsStreamingAudio] must be honest.
 */
interface StreamingTtsBackend {
    val id: String
    val kind: VoiceProviderKind
    val model: String
    val defaultVoice: String
    val supportsStreamingAudio: Boolean
    fun availability(): VoiceProviderAvailability
    fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        cancelFlag: () -> Boolean,
    ): TtsAudioResult
}

/**
 * V2-09 streaming TTS router — mirrors StreamingAsrRouter policy shape.
 */
class StreamingTtsRouter(
    private val local: StreamingTtsBackend,
    private val cloud: StreamingTtsBackend? = null,
) {
    fun decide(ctx: TtsRouteContext): TtsRouteDecision {
        val localAvail = local.availability()
        val cloudAvail = cloud?.availability() ?: VoiceProviderAvailability.UNAVAILABLE

        if (ctx.privacyRestricted || ctx.preference == TtsPreference.LOCAL_ONLY) {
            return decision(
                local,
                if (ctx.privacyRestricted) TtsRouteReason.PRIVACY else TtsRouteReason.LOCAL_ONLY,
            )
        }
        if (!ctx.isOnline) return decision(local, TtsRouteReason.OFFLINE)
        if (cloud == null || cloudAvail == VoiceProviderAvailability.UNAVAILABLE || !ctx.cloudHealthy) {
            return decision(local, TtsRouteReason.CLOUD_UNAVAILABLE)
        }
        return when (ctx.preference) {
            TtsPreference.LOCAL_ONLY -> decision(local, TtsRouteReason.LOCAL_ONLY)
            TtsPreference.CLOUD_PREFERRED -> decision(cloud, TtsRouteReason.USER_PREFERENCE)
            TtsPreference.AUTO -> {
                when {
                    localAvail != VoiceProviderAvailability.AVAILABLE ->
                        decision(cloud, TtsRouteReason.LOCAL_UNAVAILABLE)
                    ctx.preferLowLatency -> decision(local, TtsRouteReason.ONLINE_LATENCY)
                    else -> decision(cloud, TtsRouteReason.ONLINE_QUALITY)
                }
            }
        }
    }

    fun select(ctx: TtsRouteContext): StreamingTtsBackend {
        val d = decide(ctx)
        return if (d.kind == VoiceProviderKind.CLOUD && cloud != null) cloud else local
    }

    private fun decision(backend: StreamingTtsBackend, reason: TtsRouteReason) =
        TtsRouteDecision(
            providerId = backend.id,
            kind = backend.kind,
            model = backend.model,
            voice = backend.defaultVoice,
            reason = reason,
            streamingSupported = backend.supportsStreamingAudio,
        )
}

/**
 * Synthesize one sentence with cloud→local fallback without double audio responsibility.
 * Caller owns playback; this only returns PCM (or empty on cancel/fail-after).
 */
class HybridTtsSynthesizer(
    private val router: StreamingTtsRouter,
    private val local: StreamingTtsBackend,
    private val cloud: StreamingTtsBackend? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var lastCloudFailAtMs: Long = 0L
    @Volatile private var cooldownUntilMs: Long = 0L

    fun synthesizeChunk(
        text: String,
        routeCtx: TtsRouteContext,
        voice: TtsVoiceConfig,
        cancelFlag: () -> Boolean,
        telemetry: TtsTelemetry,
    ): TtsAudioResult {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            telemetry.ttsErrorCategory = TtsErrorKind.EMPTY_TEXT.name
            return TtsAudioResult(
                samples = FloatArray(0),
                sampleRateHz = voice.sampleRateHz,
                provider = local.id,
                model = local.model,
                voice = voice.voice,
                errorKind = TtsErrorKind.EMPTY_TEXT,
            )
        }
        if (cancelFlag()) {
            telemetry.ttsCancelled = true
            telemetry.ttsErrorCategory = TtsErrorKind.CANCELLED.name
            return empty(local, voice, TtsErrorKind.CANCELLED)
        }

        val now = clockMs()
        val ctx = if (now < cooldownUntilMs) {
            routeCtx.copy(cloudHealthy = false)
        } else {
            routeCtx
        }
        val decision = router.decide(ctx)
        telemetry.ttsProvider = decision.providerId
        telemetry.ttsModel = decision.model
        telemetry.ttsVoice = decision.voice
        telemetry.ttsRoute = decision.kind.name.lowercase()
        telemetry.ttsRouteReason = decision.reason.name
        telemetry.ttsStreaming = decision.streamingSupported

        val primary = if (decision.kind == VoiceProviderKind.CLOUD && cloud != null) cloud else local
        val t0 = clockMs()
        val primaryResult = runCatching {
            primary.synthesize(cleaned, voice, cancelFlag)
        }.getOrElse {
            TtsAudioResult(
                samples = FloatArray(0),
                sampleRateHz = voice.sampleRateHz,
                provider = primary.id,
                model = primary.model,
                voice = voice.voice,
                errorKind = TtsErrorKind.UNKNOWN,
            )
        }
        telemetry.ttsSetupMs = primaryResult.setupMs
        telemetry.ttsSynthesisMs = primaryResult.synthesisMs

        if (cancelFlag()) {
            telemetry.ttsCancelled = true
            telemetry.ttsErrorCategory = TtsErrorKind.CANCELLED.name
            return empty(primary, voice, TtsErrorKind.CANCELLED)
        }

        if (!primaryResult.isEmpty) {
            telemetry.ttsFirstAudioMs = (clockMs() - t0).coerceAtLeast(0L)
            return primaryResult
        }

        // Cloud failed before any audio — fallback local once. Never if already had samples.
        if (primary.kind == VoiceProviderKind.CLOUD &&
            local.availability() == VoiceProviderAvailability.AVAILABLE
        ) {
            val err = primaryResult.errorKind ?: TtsErrorKind.PROVIDER_UNAVAILABLE
            if (err == TtsErrorKind.CANCELLED) {
                telemetry.ttsCancelled = true
                telemetry.ttsErrorCategory = err.name
                return primaryResult
            }
            if (err == TtsErrorKind.AUTH || err == TtsErrorKind.RATE_LIMIT) {
                lastCloudFailAtMs = clockMs()
                cooldownUntilMs = lastCloudFailAtMs + cooldownMsFor(err)
            }
            telemetry.ttsFallback = true
            telemetry.ttsRouteReason = TtsRouteReason.FALLBACK.name
            telemetry.ttsErrorCategory = err.name
            telemetry.ttsProvider = local.id
            telemetry.ttsModel = local.model
            telemetry.ttsVoice = local.defaultVoice
            telemetry.ttsRoute = VoiceProviderKind.LOCAL.name.lowercase()
            if (cancelFlag()) {
                telemetry.ttsCancelled = true
                return empty(local, voice, TtsErrorKind.CANCELLED)
            }
            val localResult = local.synthesize(cleaned, voice, cancelFlag)
            if (!localResult.isEmpty) {
                telemetry.ttsFirstAudioMs = (clockMs() - t0).coerceAtLeast(0L)
                telemetry.ttsSynthesisMs =
                    (telemetry.ttsSynthesisMs ?: 0L) + localResult.synthesisMs
                return localResult.copy(
                    fallback = true,
                    fallbackReason = TtsRouteReason.FALLBACK,
                )
            }
            telemetry.ttsErrorCategory =
                (localResult.errorKind ?: TtsErrorKind.PROVIDER_UNAVAILABLE).name
            return localResult.copy(fallback = true, fallbackReason = TtsRouteReason.FALLBACK)
        }

        telemetry.ttsErrorCategory =
            (primaryResult.errorKind ?: TtsErrorKind.PROVIDER_UNAVAILABLE).name
        return primaryResult
    }

    private fun cooldownMsFor(err: TtsErrorKind): Long = when (err) {
        TtsErrorKind.RATE_LIMIT -> 60_000L
        TtsErrorKind.AUTH -> 300_000L
        else -> 15_000L
    }

    private fun empty(
        backend: StreamingTtsBackend,
        voice: TtsVoiceConfig,
        err: TtsErrorKind,
    ) = TtsAudioResult(
        samples = FloatArray(0),
        sampleRateHz = voice.sampleRateHz,
        provider = backend.id,
        model = backend.model,
        voice = voice.voice,
        errorKind = err,
    )
}
