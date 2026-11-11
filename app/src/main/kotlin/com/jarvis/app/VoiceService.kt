package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.core.common.model.AssistantResponseStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.nlp.ConversationWindow
import com.jarvis.core.model.AssistantState
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

    // Б4: проактивная реплика (ACTION_SAY). Отдельный путь: обычные ответы
    // не запрашивают аудиофокус и не меняют поведение.
    private val audioManager: AudioManager?
        get() = getSystemService(AudioManager::class.java)
    private val sayLock = Any()
    private var sayFocusRequest: AudioFocusRequest? = null
    private var sayFocusHeld = false
    private var sayInFlight = false

    private var announcements: AnnouncementWatcher? = null

    /** Заряд на исходе: система шлёт это один раз за разряд, поэтому надоедать не будет. */
    private val batteryLowReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val percent = getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            com.jarvis.app.proactive.ProactiveVoice.speak(context, "Сэр, заряд на исходе: $percent%.")
        }
    }

    private val coreListener = object : VoiceController.Listener {
        override fun onWake() {
            generationGate.cancelCurrent()
            BrainGraph.build(this@VoiceService).cancelActiveChatStream()
            VoiceController.stopSpeaking()
            ackJob?.cancel()
        }

        override fun onRequest(request: UserRequest) {
            if (request.text.isBlank()) return
            if (AppAgentRunner.consume(request.text)) return
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
                        // Модель сказала фразу и вызвала инструмент: результат озвучиваем следом.
                        if (streamed.toolResult != null) {
                            VoiceController.enqueueStreamSpeak(text = streamed.replyText, streamGeneration = streamGen)
                        }
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
                            if (JarvisSettings.conversationMode(this@VoiceService)) {
                                VoiceController.listenForFollowUp(ConversationWindow.CONVERSATION_MS)
                            }
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
                val isChat = (response?.route ?: trace?.route?.name) == "CHAT"
                when {
                    needsFollowUp -> VoiceController.listenForFollowUp()
                    ConversationWindow.keepListening(text, isChat, JarvisSettings.conversationMode(this)) ->
                        VoiceController.listenForFollowUp(ConversationWindow.CONVERSATION_MS)
                }
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
        registerReceiver(batteryLowReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_LOW))
        announcements = AnnouncementWatcher(this).also { it.start() }
        VoiceController.addListener(coreListener)
        coreRegistered = true
        widgetJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            JarvisEvents.assistantState.collect { JarvisWidgetProvider.refresh(this@VoiceService) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entry = intent?.getStringExtra(EXTRA_ENTRY) ?: VoiceController.lastEntry
        promiseKept = false
        val result = runCatching { handleCommand(intent, entry, startId) }.getOrElse { e ->
            Log.e(TAG, "command ${intent?.action} failed", e)
            CrashReports.record(this, "команда службы голоса упала (${intent?.action})", e)
            START_NOT_STICKY
        }
        // Запуск через startForegroundService обязывает службу показать уведомление за секунды,
        // иначе система роняет всё приложение (ForegroundServiceDidNotStartInTimeException —
        // самый частый вылет в отчётах тестеров). Если ни одна ветка этого не сделала, делаем сами.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true && !inForeground && !promiseKept) {
            placeholderForegroundAndQuit()
            return START_NOT_STICKY
        }
        return result
    }

    private fun handleCommand(intent: Intent?, entry: String, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVoice()
            ACTION_TOGGLE_WAKE -> {
                JarvisSettings.setWakeEnabled(this, !JarvisSettings.wakeEnabled(this))
                if (engine != null) {
                    if (!enterForeground(entry)) return START_NOT_STICKY
                } else {
                    stopSelf()
                }
            }
            ACTION_LIVE_START -> {
                Log.i(TAG, "onStartCommand LIVE entry=$entry")
                if (engine != null) openLiveScreen() else startVoice(liveMode = true, entry = entry)
            }
            ACTION_SAY -> {
                if (!handleProactiveSay(intent)) {
                    // Сервис не запущен — микрофон ради одной фразы не поднимаем.
                    stopSelf(startId)
                }
            }
            else -> {
                Log.i(TAG, "onStartCommand START entry=$entry")
                startVoice(liveMode = false, entry = entry)
            }
        }
        // Микрофон не дали: не просим систему перезапускать службу, иначе она упадёт по кругу.
        return if (engine != null) START_STICKY else START_NOT_STICKY
    }

    /**
     * Служба с микрофоном. Android 14+ запрещает её запуск, когда микрофон приложению недоступен
     * (запуск из фона: загрузка, виджет, служба уведомлений). Раньше это роняло весь процесс.
     */
    private fun enterForeground(entry: String): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(entry), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        inForeground = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "foreground with microphone refused: ${e.message}")
        CrashReports.record(this, "служба голоса не получила микрофон (вход: $entry)", e)
        VoiceController.setRunning(false)
        placeholderForegroundAndQuit()
        false
    }

    /**
     * Обещание «покажу уведомление» дано системе, а микрофон не дали: показываем
     * короткое уведомление без микрофона и сразу закрываемся. Без этого система
     * через несколько секунд убивает процесс целиком.
     */
    private fun placeholderForegroundAndQuit() {
        runCatching {
            val notice = buildNotification(VoiceController.ENTRY_UNKNOWN)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notice)
            }
        }.onFailure { Log.w(TAG, "placeholder foreground refused: ${it.message}") }
        promiseKept = true
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        inForeground = false
        notifyOpenApp()
        stopSelf()
    }

    /** Подсказка, когда голос не смог включиться сам. */
    private fun notifyOpenApp() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            buildNotification()
            val open = android.app.PendingIntent.getActivity(
                this, 7,
                Intent(this, com.jarvis.app.ui.JarvisActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                NOTIFICATION_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Джарвис не включил микрофон")
                    .setContentText("Откройте Джарвис, чтобы включить голос.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun startVoice(liveMode: Boolean, entry: String = VoiceController.ENTRY_UNKNOWN) {
        if (engine != null) {
            Log.d(TAG, "startVoice ignored: already running entry=$entry")
            return
        }
        Log.i(TAG, "VoicePipeline owning mic entry=$entry live=$liveMode")
        if (!enterForeground(entry)) return
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

    /**
     * Б4: озвучить готовый текст предложения. Только если VoiceService уже
     * работает, TTS-движок присоединён, Jarvis не говорит и нет активной
     * сессии/диалога. Перед речью берём AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
     * (USAGE_ASSISTANT / CONTENT_TYPE_SPEECH), после речи — abandon.
     *
     * @return false, если сервис/движок не готов (вызывающий может остановить
     *         только что созданный экземпляр сервиса).
     */
    private fun handleProactiveSay(intent: Intent): Boolean {
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            Log.i(SAY_TAG, "say skip: empty text")
            return true
        }
        if (engine == null || !VoiceController.hasEngine) {
            Log.i(SAY_TAG, "say skip: service not running")
            return false
        }
        val state = JarvisEvents.assistantState.value
        val session = VoiceController.isLiveSession ||
            state == AssistantState.LISTENING ||
            state == AssistantState.THINKING ||
            state == AssistantState.SPEAKING
        if (session) {
            Log.i(SAY_TAG, "say skip: session active state=$state")
            return true
        }
        if (VoiceController.isSpeaking) {
            Log.i(SAY_TAG, "say skip: already speaking")
            return true
        }
        synchronized(sayLock) {
            if (sayInFlight) {
                Log.i(SAY_TAG, "say skip: previous proactive phrase in flight")
                return true
            }
            val am = audioManager ?: run {
                Log.i(SAY_TAG, "say skip: no AudioManager")
                return true
            }
            val request = buildSayFocusRequest()
            val result = am.requestAudioFocus(request)
            Log.i(
                SAY_TAG,
                "say focus request result=$result gain=TRANSIENT_MAY_DUCK " +
                    "usage=ASSISTANT content=SPEECH",
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(SAY_TAG, "say skip: audio focus denied")
                return true
            }
            sayFocusRequest = request
            sayFocusHeld = true
            sayInFlight = true
        }
        Log.i(SAY_TAG, "say accepted chars=${text.length} state=$state")
        VoiceController.speak(
            text = text,
            onComplete = { finishProactiveSay("complete") },
            onFirstAudio = { ms -> Log.i(SAY_TAG, "say first-audio ${ms}ms") },
        )
        return true
    }

    private fun buildSayFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                Log.i(SAY_TAG, "say focus change=$change")
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    VoiceController.stopSpeaking()
                    finishProactiveSay("focus_lost")
                }
            }
            .build()

    private fun finishProactiveSay(reason: String) {
        synchronized(sayLock) {
            if (!sayInFlight) return
            sayInFlight = false
            val request = sayFocusRequest
            val held = sayFocusHeld
            sayFocusRequest = null
            sayFocusHeld = false
            if (held && request != null) {
                audioManager?.abandonAudioFocusRequest(request)
                Log.i(SAY_TAG, "say focus abandon reason=$reason")
            } else {
                Log.i(SAY_TAG, "say finished reason=$reason (no focus)")
            }
        }
    }

    private fun stopVoice() {
        generationGate.cancelCurrent()
        VoiceController.setRunning(false)
        // Микрофон на некоторых телефонах падает при остановке: это не повод ронять приложение.
        runCatching { engine?.stop() }.onFailure { Log.w(TAG, "engine stop: ${it.message}") }
        engine = null
        stopForeground(STOP_FOREGROUND_DETACH)
        inForeground = false
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

    private var widgetJob: kotlinx.coroutines.Job? = null
    @Volatile private var inForeground = false
    /** Эта команда уже показала уведомление (пусть и временное) — обещание системе выполнено. */
    private var promiseKept = false

    override fun onDestroy() {
        widgetJob?.cancel()
        runCatching { unregisterReceiver(batteryLowReceiver) }
        announcements?.stop()
        finishProactiveSay("destroy")
        if (coreRegistered) VoiceController.removeListener(coreListener)
        runCatching { engine?.stop() }
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
        const val ACTION_SAY = "com.jarvis.assistant.VOICE_SAY"
        const val EXTRA_ENTRY = "entry"
        /** Служба запущена через startForegroundService и обязана показать уведомление. */
        const val EXTRA_FOREGROUND = "foreground"
        const val EXTRA_TEXT = "text"
        private const val SAY_TAG = "Jarvis.ProactiveDelivery"
        private const val CHANNEL_ID = "jarvis_voice"
        private const val NOTIFICATION_ID = 1
    }
}
