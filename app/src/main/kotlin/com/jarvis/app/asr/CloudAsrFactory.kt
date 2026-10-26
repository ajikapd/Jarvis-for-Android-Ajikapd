package com.jarvis.app.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.asr.AsrModelSpec
import com.jarvis.asr.AsrSession
import com.jarvis.asr.LocalStreamingAsrBackend
import com.jarvis.core.voice.StreamingAsrBackend
import com.jarvis.core.voice.StreamingAsrRouter
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.app.JarvisSettings

/**
 * V2-08: picks a single cloud backend (Whisper-capable host preferred, else Android Speech).
 * Provider-neutral wiring — not AIRouter.
 */
object CloudAsrFactory {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun localBackend(sessionFactory: () -> AsrSession, ready: () -> Boolean): StreamingAsrBackend =
        LocalStreamingAsrBackend(
            sessionFactory = sessionFactory,
            id = "asr.local.sherpa",
            model = AsrModelSpec.RU_STREAMING.dirName,
            ready = ready,
        )

    /**
     * One cloud backend:
     * 1) OpenAI-compatible Whisper when ASR base URL + key configured
     * 2) else Android SpeechRecognizer when available
     */
    fun cloudBackend(context: Context): StreamingAsrBackend {
        val app = context.applicationContext
        val whisperUrl = JarvisSettings.asrCloudBaseUrl(app)
        val whisperKey = JarvisSettings.asrCloudApiKey(app)
        if (whisperUrl.isNotBlank() && whisperKey.isNotBlank()) {
            return OpenAiWhisperStreamingAsrBackend(
                baseUrlProvider = { JarvisSettings.asrCloudBaseUrl(app) },
                apiKeyProvider = { JarvisSettings.asrCloudApiKey(app) },
                modelProvider = { JarvisSettings.asrCloudModel(app) },
                online = { isOnline(app) },
            )
        }
        return AndroidSpeechCloudAsrBackend(
            appContext = app,
            online = { isOnline(app) },
        )
    }

    fun router(local: StreamingAsrBackend, cloud: StreamingAsrBackend?): StreamingAsrRouter =
        StreamingAsrRouter(local = local, cloud = cloud)

    fun cloudConfiguredLabel(context: Context): String {
        val cloud = cloudBackend(context)
        return when (cloud.availability()) {
            VoiceProviderAvailability.AVAILABLE -> "${cloud.id}/${cloud.model}"
            VoiceProviderAvailability.DEGRADED -> "${cloud.id}/degraded"
            VoiceProviderAvailability.UNAVAILABLE -> "${cloud.id}/unavailable"
        }
    }
}
