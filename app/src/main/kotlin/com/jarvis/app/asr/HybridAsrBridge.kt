package com.jarvis.app.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jarvis.asr.AsrSession
import com.jarvis.core.voice.AsrPreference
import com.jarvis.core.voice.AsrRouteContext
import com.jarvis.core.voice.AsrSessionConfig
import com.jarvis.core.voice.AsrSessionListener
import com.jarvis.core.voice.AsrSessionPhase
import com.jarvis.core.voice.AsrTelemetry
import com.jarvis.core.voice.HybridAsrController
import com.jarvis.core.voice.StreamingAsrBackend
import com.jarvis.core.voice.VoiceProviderKind
import com.jarvis.app.JarvisSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V2-08 glue between VoiceEngine AudioCapture and [HybridAsrController].
 * Wake/KWS ownership stays in VoiceEngine — this only runs for command / live ASR turns.
 */
class HybridAsrBridge(
    private val app: Context,
    private val localSessionFactory: () -> AsrSession,
    private val localReady: () -> Boolean,
) {
    private val localBackend: StreamingAsrBackend =
        CloudAsrFactory.localBackend(localSessionFactory, localReady)
    @Volatile private var cloudBackend: StreamingAsrBackend = CloudAsrFactory.cloudBackend(app)
    @Volatile private var router = CloudAsrFactory.router(localBackend, cloudBackend)
    @Volatile private var controller = HybridAsrController(router, localBackend, cloudBackend) {
        SystemClock.elapsedRealtime()
    }

    private val turnActive = AtomicBoolean(false)
    private val turnGeneration = AtomicLong(0)
    @Volatile var lastTelemetry: AsrTelemetry? = null
        private set
    /** True when active cloud backend owns the mic (Android Speech) — pause AudioCapture. */
    @Volatile var cloudOwnsMic: Boolean = false
        private set

    fun refreshCloud() {
        cloudBackend = CloudAsrFactory.cloudBackend(app)
        router = CloudAsrFactory.router(localBackend, cloudBackend)
        controller = HybridAsrController(router, localBackend, cloudBackend) {
            SystemClock.elapsedRealtime()
        }
    }

    fun preference(): AsrPreference =
        when (JarvisSettings.asrMode(app)) {
            "CLOUD_PREFERRED" -> AsrPreference.CLOUD_PREFERRED
            "LOCAL_ONLY" -> AsrPreference.LOCAL_ONLY
            else -> AsrPreference.AUTO
        }

    fun routeContext(privacyLocalRequired: Boolean = false): AsrRouteContext =
        AsrRouteContext(
            isOnline = CloudAsrFactory.isOnline(app),
            preference = preference(),
            privacyRestricted = privacyLocalRequired || preference() == AsrPreference.LOCAL_ONLY,
            cloudHealthy = cloudBackend.availability() ==
                com.jarvis.core.voice.VoiceProviderAvailability.AVAILABLE,
            localHealthy = localBackend.availability() ==
                com.jarvis.core.voice.VoiceProviderAvailability.AVAILABLE,
            preferLowLatency = true,
            language = "ru",
        )

    fun decisionLabel(): String {
        val d = router.decide(routeContext())
        return "selected=${d.kind.name.lowercase()} provider=${d.providerId} " +
            "model=${d.model} reason=${d.reason}"
    }

    /**
     * Start a recognition turn. Returns true if AudioCapture should pause (cloud mic owner).
     */
    fun beginTurn(
        onPartial: (String) -> Unit,
        onFinal: (text: String, telemetry: AsrTelemetry) -> Unit,
        privacyLocalRequired: Boolean = false,
        onEndWithoutFinal: () -> Unit = {},
    ): Boolean {
        cancelTurn()
        refreshCloud()
        val generation = turnGeneration.incrementAndGet()
        val turnController = controller
        val ctx = routeContext(privacyLocalRequired)
        val decision = router.decide(ctx)
        Log.i(TAG, decisionLabel())
        val requestId = "asr-${UUID.randomUUID().toString().take(8)}"
        val sessionId = "s-${UUID.randomUUID().toString().take(8)}"
        val needsMicHandoff =
            decision.kind == VoiceProviderKind.CLOUD &&
                decision.providerId == "asr.cloud.android_speech"
        cloudOwnsMic = needsMicHandoff
        turnActive.set(true)
        val listener = AsrSessionListener { result ->
            if (turnGeneration.get() != generation) return@AsrSessionListener
            when (result.phase) {
                AsrSessionPhase.PARTIAL -> {
                    if (result.text.isNotBlank()) onPartial(result.text)
                }
                AsrSessionPhase.FINAL -> {
                    val tel = turnController.currentTelemetry() ?: AsrTelemetry(
                        asrProvider = result.provider,
                        asrModel = result.model,
                        asrRoute = decision.kind.name.lowercase(),
                        asrRouteReason = decision.reason.name,
                        asrFallback = result.fallback,
                        asrFallbackReason = result.fallbackReason?.name,
                        asrSetupMs = result.setupMs,
                        asrFirstPartialMs = result.firstPartialMs,
                        asrFinalizeMs = result.finalizeMs,
                        asrTotalMs = result.totalMs,
                    )
                    lastTelemetry = tel
                    Log.i(TAG, tel.summary())
                    turnActive.set(false)
                    cloudOwnsMic = false
                    if (result.text.isNotBlank()) {
                        onFinal(result.text, tel)
                    } else {
                        onEndWithoutFinal()
                    }
                }
                AsrSessionPhase.ERROR, AsrSessionPhase.CANCEL -> {
                    turnActive.set(false)
                    cloudOwnsMic = false
                    onEndWithoutFinal()
                }
                else -> Unit
            }
        }
        lastTelemetry = turnController.start(
            config = AsrSessionConfig(
                requestId = requestId,
                sessionId = sessionId,
                language = "ru-RU",
                privacyLocalRequired = privacyLocalRequired,
                vocabularyHints = DEFAULT_HINTS,
            ),
            routeCtx = ctx,
            listener = listener,
        )
        return needsMicHandoff
    }

    fun feed(samples: FloatArray) {
        if (!turnActive.get() || cloudOwnsMic) return
        controller.feedFloatPcm(samples)
    }

    fun finalizeTurn() {
        if (!turnActive.get()) return
        controller.finalizeUtterance()
    }

    fun cancelTurn() {
        turnGeneration.incrementAndGet()
        turnActive.set(false)
        cloudOwnsMic = false
        controller.cancel(com.jarvis.core.voice.AsrCancelReason.NEW_INTERACTION)
    }

    fun isTurnActive(): Boolean = turnActive.get()

    companion object {
        private const val TAG = "Jarvis.Asr"
        val DEFAULT_HINTS = listOf(
            "Telegram", "WhatsApp", "YouTube", "браузер", "Джарвис",
        )
    }
}
