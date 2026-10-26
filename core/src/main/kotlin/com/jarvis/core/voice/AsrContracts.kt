package com.jarvis.core.voice

/**
 * V2-08: provider-neutral streaming ASR contracts.
 * Separate domain from [com.jarvis.brain.aiprovider.AIRouter].
 */

enum class AsrPreference {
    /** Online + healthy cloud → cloud; else local. Privacy always wins. */
    AUTO,
    /** Prefer cloud when allowed; fall back to local. */
    CLOUD_PREFERRED,
    /** Never send audio to cloud. */
    LOCAL_ONLY,
}

enum class AsrRouteReason {
    OFFLINE,
    PRIVACY,
    LOCAL_ONLY,
    USER_PREFERENCE,
    CLOUD_UNAVAILABLE,
    CLOUD_DEGRADED,
    LOCAL_UNAVAILABLE,
    ONLINE_LATENCY,
    ONLINE_QUALITY,
    PROVIDER_HEALTH,
    FALLBACK,
}

enum class AsrErrorKind {
    NETWORK,
    AUTH,
    TIMEOUT,
    UNAVAILABLE,
    RATE_LIMIT,
    INVALID_AUDIO,
    UNSUPPORTED,
    CANCELLED,
    INTERNAL,
}

enum class AsrCancelReason {
    USER_INTERRUPTION,
    REQUEST_CANCELLED,
    SERVICE_DESTROY,
    NEW_INTERACTION,
    TIMEOUT,
    PROVIDER_FAILURE,
    BARGE_IN,
}

enum class AsrSessionPhase {
    START,
    PARTIAL,
    FINAL,
    COMPLETE,
    CANCEL,
    ERROR,
}

/**
 * Confidence from provider only. Never invent scores.
 */
sealed class AsrConfidence {
    data object Unknown : AsrConfidence()
    data class Known(val value: Float) : AsrConfidence()
}

data class AsrRouteDecision(
    val providerId: String,
    val kind: VoiceProviderKind,
    val model: String,
    val reason: AsrRouteReason,
    val streaming: Boolean,
)

data class AsrResult(
    val requestId: String,
    val text: String,
    val isFinal: Boolean,
    val confidence: AsrConfidence = AsrConfidence.Unknown,
    val language: String = "ru",
    val provider: String,
    val model: String,
    val sessionId: String,
    val phase: AsrSessionPhase = if (isFinal) AsrSessionPhase.FINAL else AsrSessionPhase.PARTIAL,
    val setupMs: Long? = null,
    val firstPartialMs: Long? = null,
    val finalizeMs: Long? = null,
    val totalMs: Long? = null,
    val fallback: Boolean = false,
    val fallbackReason: AsrRouteReason? = null,
    val errorKind: AsrErrorKind? = null,
)

data class AsrSessionConfig(
    val requestId: String,
    val sessionId: String,
    val language: String = "ru-RU",
    val sampleRateHz: Int = 16_000,
    val maxBufferedMs: Int = 30_000,
    val vocabularyHints: List<String> = emptyList(),
    val privacyLocalRequired: Boolean = false,
)

data class AsrRouteContext(
    val isOnline: Boolean,
    val preference: AsrPreference = AsrPreference.AUTO,
    val privacyRestricted: Boolean = false,
    val cloudHealthy: Boolean = true,
    val localHealthy: Boolean = true,
    val preferLowLatency: Boolean = true,
    val language: String = "ru",
)

/**
 * Streaming ASR backend — receives live PCM chunks, emits partial then final.
 */
interface StreamingAsrBackend {
    val id: String
    val kind: VoiceProviderKind
    val model: String
    val supportsStreamingPartials: Boolean
    fun availability(): VoiceProviderAvailability
    fun openSession(config: AsrSessionConfig, listener: AsrSessionListener): StreamingAsrSession
}

fun interface AsrSessionListener {
    fun onEvent(result: AsrResult)
}

interface StreamingAsrSession {
    val sessionId: String
    val providerId: String
    fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int = 16_000)
    fun feedPcm16le(bytes: ByteArray, sampleRateHz: Int = 16_000)
    fun finalizeUtterance()
    fun cancel(reason: AsrCancelReason)
    fun isActive(): Boolean
}

/** Telemetry bag filled by HybridAsrController / VoiceEngine. */
data class AsrTelemetry(
    var asrProvider: String? = null,
    var asrModel: String? = null,
    var asrRoute: String? = null,
    var asrRouteReason: String? = null,
    var asrSessionStartAt: Long? = null,
    var asrFirstPartialAt: Long? = null,
    var asrFirstStablePartialAt: Long? = null,
    var asrFinalAt: Long? = null,
    var asrSetupMs: Long? = null,
    var asrFirstPartialMs: Long? = null,
    var asrFinalizeMs: Long? = null,
    var asrTotalMs: Long? = null,
    var asrFallback: Boolean = false,
    var asrFallbackReason: String? = null,
) {
    fun summary(): String =
        "asr provider=${asrProvider ?: "-"} model=${asrModel ?: "-"} " +
            "route=${asrRoute ?: "-"} reason=${asrRouteReason ?: "-"} " +
            "setup=${asrSetupMs ?: "-"} firstPartial=${asrFirstPartialMs ?: "-"} " +
            "final=${asrFinalizeMs ?: "-"} total=${asrTotalMs ?: "-"} " +
            "fallback=$asrFallback/${asrFallbackReason ?: "-"}"
}
