package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.core.common.model.AssistantResponseStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.privacy.SensitivityClassifier
import com.jarvis.core.runtime.ImmediateAckPolicy
import com.jarvis.core.runtime.InteractionGenerationGate
import com.jarvis.core.runtime.LatencyStatsStore
import com.jarvis.core.runtime.RouteDecision
import com.jarvis.core.runtime.applyLatency
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground-сервис: держит микрофон и пайплайн в фоне.
 * V2-07: real CHAT streaming → sentence TTS queue when cloud stream available.
 */
class VoiceService : Service() {

    private val brainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-brain").apply {
            priority = Thread.NORM_PRIORITY + 2
        }
    }
    private val coreScope = CoroutineScope(SupervisorJob() + brainExecutor.asCoroutineDispatcher())
    private var coreRegistered = false
    private var engine: VoiceEngine? = null
    private val generationGate = InteractionGenerationGate()
    private var ackJob: Job? = null

    private val coreListener = object : VoiceController.Listener {
        override fun onWake() {
            generationGate.cancelCurrent()
            BrainGraph.build(this@VoiceService).cancelActiveChatStream()
            VoiceController.stopSpeaking()
            ackJob?.cancel()
        }

        override fun onRequest(request: UserRequest) {
            if (request.text.isBlank()) return
            val gen = generationGate.begin()
            BrainGraph.build(this@VoiceService).cancelActiveChatStream()
            VoiceController.stopSpeaking()
            ackJob?.cancel()
            coreScope.launch {
                Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
                VoiceController.setProcessingBusy(true)
                val t0 = SystemClock.elapsedRealtime()
                val orch = BrainGraph.build(this@VoiceService)

                val maybeAck = ImmediateAckPolicy.acknowledgementFor(
                    RouteDecision.CHAT,
                    request.text,
                )
                if (maybeAck != null) {
                    ackJob = launch {
                        delay(ACK_DELAY_MS)
                        if (!generationGate.isStale(gen)) {
                            orch.lastTrace?.interactionLatency?.firstAckAt =
                                SystemClock.elapsedRealtime()
                            VoiceController.speak(maybeAck)
                        }
                    }
                }

                val firstAudioOnce = AtomicBoolean(false)
                var streamGen = 0
                var usedStream = false

                val streamed = orch.processStreamingChat(
                    request = request,
                    generationId = gen,
                    isGenerationStale = { generationGate.isStale(gen) },
                    onSentence = { sentence, first ->
                        if (generationGate.isStale(gen)) return@processStreamingChat false
                        ackJob?.cancel()
                        if (first) {
                            VoiceController.stopSpeaking()
                            streamGen = VoiceController.beginStreamSpeak()
                            usedStream = true
                            orch.lastTrace?.firstSentenceMs =
                                SystemClock.elapsedRealtime() - t0
                            orch.lastTrace?.firstTextChunkMs =
                                SystemClock.elapsedRealtime() - t0
                        }
                        VoiceController.enqueueStreamSpeak(
                            text = sentence,
                            streamGeneration = streamGen,
                            onFirstAudio = { ttsMs ->
                                if (firstAudioOnce.compareAndSet(false, true)) {
                                    VoiceController.setProcessingBusy(false)
                                    val e2e = SystemClock.elapsedRealtime() - t0
                                    orch.lastTrace?.firstAudioMs = e2e
                                    orch.lastTrace?.ttsStartupMs = ttsMs
                                    orch.lastTrace?.ttsFirstAudioMs = e2e
                                    orch.lastTrace?.e2eMs = e2e
                                    orch.lastTrace?.interactionLatency?.ttsFirstAudioAt =
                                        SystemClock.elapsedRealtime()
                                    Log.d(
                                        TAG,
                                        "stream first-audio e2e=${e2e}ms tts=$ttsMs " +
                                            "slo1s=${e2e <= 1000}",
                                    )
                                }
                            },
                        )
                        true
                    },
                )

                if (generationGate.isStale(gen)) {
                    Log.i(TAG, "stale request ${request.requestId} — skip")
                    VoiceController.setProcessingBusy(false)
                    return@launch
                }

                if (streamed != null) {
                    ackJob?.cancel()
                    val brainMs = SystemClock.elapsedRealtime() - t0
                    val trace = orch.lastTrace
                    val lat = trace?.interactionLatency
                    lat?.firstUsefulAt = SystemClock.elapsedRealtime()
                    Log.d(
                        TAG,
                        "stream-chat ${brainMs}ms status=${streamed.status} " +
                            "streamUsed=${orch.lastChatStreamResult?.streamed} " +
                            "ttft=${orch.lastChatStreamResult?.ttftMs} " +
                            "sentences=${orch.lastChatStreamResult?.sentenceCount} " +
                            "reply=\"${SensitivityClassifier.mask(streamed.replyText).take(120)}\"",
                    )
                    if (usedStream) {
                        VoiceController.endStreamSpeak {
                            if (generationGate.isStale(gen)) return@endStreamSpeak
                            lat?.ttsCompleteAt = SystemClock.elapsedRealtime()
                            lat?.interactionCompleteAt = SystemClock.elapsedRealtime()
                            if (lat != null && trace != null) {
                                trace.applyLatency(lat)
                                Log.i("Jarvis.Latency", lat.summaryLine())
                                LatencyStatsStore.recordFrom(trace)
                            }
                            VoiceController.setProcessingBusy(false)
                        }
                    } else {
                        // Fallback non-stream path inside streaming attempt
                        speakFull(streamed.replyText, gen, t0, brainMs, orch)
                    }
                    return@launch
                }

                // Non-streaming routes (FAST/TEMPLATE/…) or stream unavailable
                val response = try {
                    orch.process(request)
                } catch (t: Throwable) {
                    VoiceController.setProcessingBusy(false)
                    throw t
                }
                if (generationGate.isStale(gen)) {
                    VoiceController.setProcessingBusy(false)
                    return@launch
                }
                ackJob?.cancel()
                VoiceController.stopSpeaking()
                val brainMs = SystemClock.elapsedRealtime() - t0
                speakFull(response.replyText, gen, t0, brainMs, orch, response)
            }
        }
    }

    private fun speakFull(
        text: String,
        gen: Long,
        t0: Long,
        brainMs: Long,
        orch: com.jarvis.brain.AssistantOrchestrator,
        response: com.jarvis.core.common.model.AssistantResponse? = null,
    ) {
        val trace = orch.lastTrace
        val lat = trace?.interactionLatency
        lat?.firstUsefulAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "brain ${brainMs}ms status=${response?.status} " +
                "route=${response?.route ?: trace?.route} " +
                "orch=${response?.durationMs ?: brainMs}ms " +
                "reply=\"${SensitivityClassifier.mask(text).take(120)}\"",
        )
        val needsFollowUp = response?.status == AssistantResponseStatus.NEEDS_CONFIRMATION ||
            response?.status == AssistantResponseStatus.NEEDS_CLARIFICATION
        lat?.ttsStartAt = SystemClock.elapsedRealtime()
        VoiceController.speak(
            text,
            onComplete = {
                if (generationGate.isStale(gen)) return@speak
                lat?.ttsCompleteAt = SystemClock.elapsedRealtime()
                lat?.interactionCompleteAt = SystemClock.elapsedRealtime()
                if (lat != null && trace != null) {
                    trace.applyLatency(lat)
                    Log.i("Jarvis.Latency", lat.summaryLine())
                    LatencyStatsStore.recordFrom(trace)
                }
                VoiceController.setProcessingBusy(false)
                if (needsFollowUp) VoiceController.listenForFollowUp()
            },
            onFirstAudio = { ttsFirstMs ->
                if (generationGate.isStale(gen)) return@speak
                VoiceController.setProcessingBusy(false)
                val firstAudioAt = SystemClock.elapsedRealtime()
                lat?.ttsFirstAudioAt = firstAudioAt
                val firstAudioE2e = firstAudioAt - t0
                Log.d(
                    TAG,
                    "e2e first-audio ${firstAudioE2e}ms (brain=${brainMs}ms tts=$ttsFirstMs) " +
                        "slo1s=${firstAudioE2e <= 1000}",
                )
                if (trace != null) {
                    trace.firstAudioMs = firstAudioE2e
                    trace.ttsStartupMs = ttsFirstMs
                    trace.ttsFirstAudioMs = firstAudioE2e
                    trace.e2eMs = firstAudioE2e
                }
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        VoiceController.addListener(coreListener)
        coreRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entry = intent?.getStringExtra(EXTRA_ENTRY) ?: VoiceController.lastEntry
        when (intent?.action) {
            ACTION_STOP -> stopVoice()
            ACTION_TOGGLE_WAKE -> {
                JarvisSettings.setWakeEnabled(this, !JarvisSettings.wakeEnabled(this))
                if (engine != null) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(entry),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                } else {
                    stopSelf()
                }
            }
            ACTION_LIVE_START -> {
                Log.i(TAG, "onStartCommand LIVE entry=$entry")
                if (engine != null) openLiveScreen() else startVoice(liveMode = true, entry = entry)
            }
            else -> {
                Log.i(TAG, "onStartCommand START entry=$entry")
                startVoice(liveMode = false, entry = entry)
            }
        }
        return START_STICKY
    }

    private fun startVoice(liveMode: Boolean, entry: String = VoiceController.ENTRY_UNKNOWN) {
        if (engine != null) {
            Log.d(TAG, "startVoice ignored: already running entry=$entry")
            return
        }
        Log.i(TAG, "VoicePipeline owning mic entry=$entry live=$liveMode")
        startForeground(
            NOTIFICATION_ID,
            buildNotification(entry),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val voiceEngine = VoiceEngine(
            this,
            onWake = { VoiceController.notifyWake() },
            onRequest = { VoiceController.notifyRequest(it) },
            onPartial = { VoiceController.notifyPartial(it) },
            liveMode = liveMode,
            wakeEnabled = JarvisSettings.wakeEnabled(this),
        )
        if (!voiceEngine.start()) {
            voiceEngine.stop()
            stopVoice()
            return
        }
        engine = voiceEngine
        VoiceController.attachEngine(voiceEngine)
        VoiceController.setRunning(true)

        if (!JarvisSettings.lowPowerMode(this)) {
            BrainGraph.warmLlmBrain(this)
        } else {
            BrainGraph.warmChatLlm(this)
            Log.d(TAG, "low-power: chat warm only")
        }
    }

    private fun stopVoice() {
        generationGate.cancelCurrent()
        VoiceController.setRunning(false)
        engine?.stop()
        engine = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(entry: String = VoiceController.ENTRY_UNKNOWN): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val suffix = when (entry) {
            VoiceController.ENTRY_SYSTEM_ASSIST -> " · системный помощник"
            else -> ""
        }
        val wakeOn = JarvisSettings.wakeEnabled(this)
        val silent = !JarvisSettings.prefs(this).getBoolean("companion_bg_notify", true)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(getString(R.string.voice_notification_text) + suffix)
            .setOngoing(true)
            .setSilent(silent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_fui_mic, if (wakeOn) "MUTE" else "WAKE", serviceAction(ACTION_TOGGLE_WAKE))
            .addAction(R.drawable.ic_fui_live, "LIVE MODE", serviceAction(ACTION_LIVE_START))
            .addAction(R.drawable.ic_fui_stop, "STOP SERVICE", serviceAction(ACTION_STOP))
            .build()
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(this, VoiceService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openLiveScreen() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra("open_live", true)
        startActivity(launch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (coreRegistered) VoiceController.removeListener(coreListener)
        engine?.stop()
        engine = null
        coreScope.cancel()
        brainExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Jarvis.VoiceService"
        private const val ACK_DELAY_MS = 700L
        const val ACTION_START = "com.jarvis.assistant.VOICE_START"
        const val ACTION_LIVE_START = "com.jarvis.assistant.VOICE_LIVE_START"
        const val ACTION_STOP = "com.jarvis.assistant.VOICE_STOP"
        const val ACTION_TOGGLE_WAKE = "com.jarvis.assistant.VOICE_TOGGLE_WAKE"
        const val EXTRA_ENTRY = "entry"
        private const val CHANNEL_ID = "jarvis_voice"
        private const val NOTIFICATION_ID = 1
    }
}
