package com.jarvis.core.voice

/**
 * Stage 22 + V2-08: Hybrid Voice Stack — ASR/TTS Router.
 * Wake pipeline не трогаем.
 *
 * Batch [AsrProvider] stubs remain for Stage 22 tests / diagnostics.
 * Live mic path uses [StreamingAsrBackend] + [StreamingAsrRouter] + [HybridAsrController].
 */
enum class VoiceProviderKind { LOCAL, CLOUD }

enum class VoiceProviderAvailability { AVAILABLE, UNAVAILABLE, DEGRADED }

interface AsrProvider {
    val id: String
    val kind: VoiceProviderKind
    fun availability(): VoiceProviderAvailability
    /** Sync stub: null = failure. */
    fun transcribe(pcm16le: ByteArray, sampleRateHz: Int = 16_000): String?
}

interface TtsProvider {
    val id: String
    val kind: VoiceProviderKind
    fun availability(): VoiceProviderAvailability
    /** Sync stub: empty = failure. */
    fun synthesize(text: String): ByteArray?
}

data class VoiceRouteContext(
    val isOnline: Boolean,
    val preferCloud: Boolean = true,
    val privacyRestricted: Boolean = false,
)

/**
 * Legacy batch ASR router (Stage 22). Prefer [StreamingAsrRouter] for mic path.
 */
class AsrRouter(
    private val local: AsrProvider,
    private val cloud: AsrProvider? = null,
) {
    fun select(ctx: VoiceRouteContext): AsrProvider {
        if (ctx.privacyRestricted || !ctx.isOnline || !ctx.preferCloud) return local
        val c = cloud
        return if (c != null && c.availability() == VoiceProviderAvailability.AVAILABLE) c else local
    }
}

class TtsRouter(
    private val local: TtsProvider,
    private val cloud: TtsProvider? = null,
) {
    fun select(ctx: VoiceRouteContext): TtsProvider {
        if (ctx.privacyRestricted || !ctx.isOnline || !ctx.preferCloud) return local
        val c = cloud
        return if (c != null && c.availability() == VoiceProviderAvailability.AVAILABLE) c else local
    }
}

/** Local stubs that wrap existing engines later. */
class LocalAsrStub(
    override val id: String = "asr.local",
    private val ready: Boolean = true,
) : AsrProvider {
    override val kind = VoiceProviderKind.LOCAL
    override fun availability() =
        if (ready) VoiceProviderAvailability.AVAILABLE else VoiceProviderAvailability.UNAVAILABLE
    override fun transcribe(pcm16le: ByteArray, sampleRateHz: Int): String? =
        if (ready && pcm16le.isNotEmpty()) "local-transcript" else null
}

class LocalTtsStub(
    override val id: String = "tts.local",
    private val ready: Boolean = true,
) : TtsProvider {
    override val kind = VoiceProviderKind.LOCAL
    override fun availability() =
        if (ready) VoiceProviderAvailability.AVAILABLE else VoiceProviderAvailability.UNAVAILABLE
    override fun synthesize(text: String): ByteArray? =
        if (ready && text.isNotBlank()) ByteArray(8) { 1 } else null
}

/**
 * Cloud stubs — retained for Stage 22 compatibility.
 * V2-08 live path uses real [StreamingAsrBackend] implementations, not this stub.
 */
class CloudAsrStub(
    override val id: String = "asr.cloud",
    private val configured: Boolean = false,
) : AsrProvider {
    override val kind = VoiceProviderKind.CLOUD
    override fun availability() =
        if (configured) VoiceProviderAvailability.AVAILABLE else VoiceProviderAvailability.UNAVAILABLE
    override fun transcribe(pcm16le: ByteArray, sampleRateHz: Int): String? =
        if (configured) "cloud-transcript" else null
}

class CloudTtsStub(
    override val id: String = "tts.cloud",
    private val configured: Boolean = false,
) : TtsProvider {
    override val kind = VoiceProviderKind.CLOUD
    override fun availability() =
        if (configured) VoiceProviderAvailability.AVAILABLE else VoiceProviderAvailability.UNAVAILABLE
    override fun synthesize(text: String): ByteArray? =
        if (configured && text.isNotBlank()) ByteArray(16) { 2 } else null
}

/**
 * V2-08 streaming ASR router — distinct from AIRouter.
 */
class StreamingAsrRouter(
    private val local: StreamingAsrBackend,
    private val cloud: StreamingAsrBackend? = null,
) {
    fun decide(ctx: AsrRouteContext): AsrRouteDecision {
        val localAvail = local.availability()
        val cloudAvail = cloud?.availability() ?: VoiceProviderAvailability.UNAVAILABLE

        if (ctx.privacyRestricted || ctx.preference == AsrPreference.LOCAL_ONLY) {
            return decision(local, AsrRouteReason.PRIVACY.takeIf { ctx.privacyRestricted }
                ?: AsrRouteReason.LOCAL_ONLY)
        }
        if (!ctx.isOnline) {
            return decision(local, AsrRouteReason.OFFLINE)
        }
        if (cloud == null || cloudAvail == VoiceProviderAvailability.UNAVAILABLE || !ctx.cloudHealthy) {
            return decision(local, AsrRouteReason.CLOUD_UNAVAILABLE)
        }
        if (localAvail == VoiceProviderAvailability.UNAVAILABLE && !ctx.localHealthy) {
            return decision(cloud, AsrRouteReason.LOCAL_UNAVAILABLE)
        }
        return when (ctx.preference) {
            AsrPreference.LOCAL_ONLY -> decision(local, AsrRouteReason.LOCAL_ONLY)
            AsrPreference.CLOUD_PREFERRED -> {
                if (cloudAvail == VoiceProviderAvailability.AVAILABLE && ctx.cloudHealthy) {
                    decision(cloud!!, AsrRouteReason.USER_PREFERENCE)
                } else {
                    decision(local, AsrRouteReason.CLOUD_UNAVAILABLE)
                }
            }
            AsrPreference.AUTO -> {
                // Both healthy: prefer local for latency; cloud when latency preference off.
                when {
                    cloudAvail != VoiceProviderAvailability.AVAILABLE || !ctx.cloudHealthy ->
                        decision(local, AsrRouteReason.CLOUD_UNAVAILABLE)
                    localAvail != VoiceProviderAvailability.AVAILABLE ->
                        decision(cloud!!, AsrRouteReason.LOCAL_UNAVAILABLE)
                    ctx.preferLowLatency ->
                        decision(local, AsrRouteReason.ONLINE_LATENCY)
                    else ->
                        decision(cloud!!, AsrRouteReason.ONLINE_QUALITY)
                }
            }
        }
    }

    fun select(ctx: AsrRouteContext): StreamingAsrBackend {
        val d = decide(ctx)
        return if (d.kind == VoiceProviderKind.CLOUD && cloud != null) cloud else local
    }

    private fun decision(backend: StreamingAsrBackend, reason: AsrRouteReason) =
        AsrRouteDecision(
            providerId = backend.id,
            kind = backend.kind,
            model = backend.model,
            reason = reason,
            streaming = backend.supportsStreamingPartials,
        )
}
