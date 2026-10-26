package com.jarvis.app.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.app.JarvisSettings
import com.jarvis.core.voice.HybridTtsSynthesizer
import com.jarvis.core.voice.StreamingTtsBackend
import com.jarvis.core.voice.StreamingTtsRouter
import com.jarvis.core.voice.TtsPreference
import com.jarvis.core.voice.TtsRouteContext
import com.jarvis.core.voice.TtsTelemetry
import com.jarvis.core.voice.TtsVoiceConfig
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.tts.JarvisTts
import com.jarvis.tts.LocalPiperTtsBackend

object CloudTtsFactory {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun localBackend(engine: () -> JarvisTts?): StreamingTtsBackend =
        LocalPiperTtsBackend(engine = engine)

    /**
     * One cloud backend: OpenAI speech when URL+key set, else Android TextToSpeech.
     */
    fun cloudBackend(context: Context): StreamingTtsBackend {
        val app = context.applicationContext
        val url = JarvisSettings.ttsCloudBaseUrl(app)
        val key = JarvisSettings.ttsCloudApiKey(app)
        if (url.isNotBlank() && key.isNotBlank()) {
            return OpenAiSpeechTtsBackend(
                baseUrlProvider = { JarvisSettings.ttsCloudBaseUrl(app) },
                apiKeyProvider = { JarvisSettings.ttsCloudApiKey(app) },
                modelProvider = { JarvisSettings.ttsCloudModel(app) },
                voiceProvider = { JarvisSettings.ttsCloudVoice(app) },
                online = { isOnline(app) },
            )
        }
        return AndroidSystemTtsBackend(app, online = { isOnline(app) })
    }

    fun cloudConfiguredLabel(context: Context): String {
        val c = cloudBackend(context)
        return when (c.availability()) {
            VoiceProviderAvailability.AVAILABLE -> "${c.id}/${c.model}/${c.defaultVoice}"
            VoiceProviderAvailability.DEGRADED -> "${c.id}/degraded"
            VoiceProviderAvailability.UNAVAILABLE -> "${c.id}/unavailable"
        }
    }
}

/**
 * Glue for JarvisSpeaker — single hybrid synthesizer, one playback owner stays TtsPlayer.
 */
class HybridTtsBridge(
    private val app: Context,
    private val localEngine: () -> JarvisTts?,
) {
    private val local = CloudTtsFactory.localBackend(localEngine)
    @Volatile private var cloud = CloudTtsFactory.cloudBackend(app)
    @Volatile private var router = StreamingTtsRouter(local, cloud)
    @Volatile private var synth = HybridTtsSynthesizer(router, local, cloud)

    @Volatile var lastTelemetry: TtsTelemetry = TtsTelemetry()
        private set

    fun refresh() {
        cloud = CloudTtsFactory.cloudBackend(app)
        router = StreamingTtsRouter(local, cloud)
        synth = HybridTtsSynthesizer(router, local, cloud)
    }

    fun preference(): TtsPreference =
        when (JarvisSettings.ttsMode(app)) {
            "CLOUD_PREFERRED" -> TtsPreference.CLOUD_PREFERRED
            "LOCAL_ONLY" -> TtsPreference.LOCAL_ONLY
            else -> TtsPreference.AUTO
        }

    fun routeContext(): TtsRouteContext =
        TtsRouteContext(
            isOnline = CloudTtsFactory.isOnline(app),
            preference = preference(),
            privacyRestricted = preference() == TtsPreference.LOCAL_ONLY,
            cloudHealthy = cloud.availability() == VoiceProviderAvailability.AVAILABLE,
            localHealthy = local.availability() == VoiceProviderAvailability.AVAILABLE,
            preferLowLatency = true,
            language = "ru",
        )

    fun decisionLabel(): String {
        val d = router.decide(routeContext())
        return "selected=${d.kind.name.lowercase()} provider=${d.providerId} " +
            "model=${d.model} voice=${d.voice} reason=${d.reason}"
    }

    fun voiceConfig(): TtsVoiceConfig =
        TtsVoiceConfig(
            language = "ru-RU",
            voice = JarvisSettings.ttsCloudVoice(app).ifBlank { "default" },
            model = JarvisSettings.ttsCloudModel(app),
            speakingRate = 1.0f,
            pitch = 1.0f,
            sampleRateHz = 22_050,
        )

    fun synthesize(
        text: String,
        cancelFlag: () -> Boolean,
        queueDepth: Int = 0,
    ): com.jarvis.core.voice.TtsAudioResult {
        refresh()
        val tel = TtsTelemetry(ttsSessionId = "tts-${System.nanoTime()}", ttsQueueDepth = queueDepth)
        val result = synth.synthesizeChunk(
            text = text,
            routeCtx = routeContext(),
            voice = voiceConfig(),
            cancelFlag = cancelFlag,
            telemetry = tel,
        )
        lastTelemetry = tel
        android.util.Log.i("Jarvis.Tts", tel.summary())
        return result
    }
}
