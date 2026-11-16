package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.asr.AsrModelSpec
import com.jarvis.asr.AsrSession
import com.jarvis.asr.BufferedEnAsr
import com.jarvis.asr.CommandPcmRefiner
import com.jarvis.asr.CommandRefinePasses
import com.jarvis.asr.OfflineMultilingualAsr
import com.jarvis.asr.SherpaAsrSession
import com.jarvis.app.asr.HybridAsrBridge
import com.jarvis.audio.AudioCapture
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import com.jarvis.core.nlp.CommandTextSelector
import com.jarvis.core.voice.AsrPreference
import com.jarvis.wakeword.WakeWordEngine
import com.jarvis.asr.SherpaKeywordSpotter
import com.jarvis.wakeword.OwwWakeSpotter
import com.jarvis.wakeword.RecentAudio
import com.jarvis.wakeword.WakeSpeechGate
import com.jarvis.wakeword.WakeWordState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Единственный голосовой пайплайн процесса. И DebugCommandReceiver, и TestConsoleActivity
 * работают через [VoiceController], поэтому два параллельных «слушателя» невозможны —
 * именно они давали двойной бип и двойную фразу.
 */
class VoiceEngine(
    private val app: Context,
    private val onWake: () -> Unit,
    private val onRequest: (UserRequest) -> Unit,
    private val onPartial: (String) -> Unit = {},
    private val liveMode: Boolean = false,
    private val wakeEnabled: Boolean = true,
) {
    private val sessionId = "V${++sessionCounter}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioCapture: AudioCapture? = null
    private var asrSession: AsrSession? = null
    private var offlineAsr: OfflineMultilingualAsr? = null
    private var enAsr: BufferedEnAsr? = null
    private var wakeWordEngine: WakeWordEngine? = null
    private var kwsSpotter: OwwWakeSpotter? = null
    private var keywordSpotter: SherpaKeywordSpotter? = null
    private val speechGate = WakeSpeechGate()
    private val recentAudio = RecentAudio(VOICE_CHECK_SAMPLES)
    private var sampleTake: SampleTake? = null
    private var micRestartAtMs = 0L
    private var micRestarts = 0
    /** Когда детекторы последний раз получали звук: если давно, глух не микрофон, а конвейер. */
    @Volatile private var wakeFedAtMs = 0L
    private var speaker: JarvisSpeaker? = null

    fun reloadVoice(onReady: (() -> Unit)? = null) {
        speaker?.reloadVoice(onReady)
    }
    @Volatile private var disposed = false
    /** Пока Brain/LLM думает — не гоняем ASR (иначе tok/s падает в 2–3 раза). */
    @Volatile var processingBusy: Boolean = false
    private var overlayView: android.view.View? = null
    private var overlayScope: CoroutineScope? = null
    /** PCM буфер для live/continuous режима (offline refine на endpoint). */
    private val livePcm = ArrayList<Float>(16000 * 6)
    private val livePcmLock = Any()
    /** Каркас неполной команды («включи песню»), пока дослушиваем EN-название. */
    @Volatile private var pendingLivePrefix: String = ""
    /** V2-08 hybrid ASR (local Sherpa + cloud). Wake/KWS ownership unchanged. */
    private var hybridAsr: HybridAsrBridge? = null
    /** Cloud Android Speech owns mic — skip AudioCapture→ASR feed. */
    @Volatile private var pauseCaptureForCloudAsr: Boolean = false
    private val hybridFinalMutex = Mutex()
    @Volatile private var lastHybridFinalAtMs: Long = 0L
    /** When true, WakeWordEngine finals are ignored (cloud owns the turn). */
    @Volatile private var suppressLocalWakeFinals: Boolean = false

    /** Запуск асинхронный — модель грузится в фоне, main-поток не блокируется (иначе ANR). */
    fun start(): Boolean {
        log("starting")
        restoreVolume()
        JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.STARTING))
        if (!AudioCapture(app).hasPermission()) {
            log("RECORD_AUDIO permission denied (adb shell pm grant com.jarvis.assistant android.permission.RECORD_AUDIO)")
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.ERROR))
            return false
        }

        val modelsRoot = File(app.getExternalFilesDir(null), "models")
        val ruDir = File(modelsRoot, AsrModelSpec.RU_STREAMING.dirName)
        if (!ruDir.isDirectory) ruDir.mkdirs()
        if (!ruDir.isDirectory) {
            log("model dir missing: ${ruDir.absolutePath}")
            JarvisEvents.publish(AssistantEvent.Error("VoiceEngine", "ASR model directory is missing"))
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.ERROR))
            return false
        }

        scope.launch {
            try {
                // TTS раньше ASR — иначе ранние DEBUG_COMMAND получают «TTS not ready».
                speaker = JarvisSpeaker(app)

                val loadStart = SystemClock.elapsedRealtime()
                if (!ensureModelFiles(ruDir, AsrModelSpec.RU_STREAMING)) {
                    log("RU model files missing in assets")
                    JarvisEvents.publish(AssistantEvent.Error("VoiceEngine", "ASR model files missing in assets"))
                    JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.ERROR))
                    return@launch
                }
                log("loading RU ASR")
                val ru = SherpaAsrSession(ruDir.absolutePath, AsrModelSpec.RU_STREAMING)
                asrSession = ru
                offlineAsr = runCatching {
                    val multiDir = File(modelsRoot, OfflineMultilingualAsr.MODEL_DIR)
                    if (!multiDir.isDirectory) multiDir.mkdirs()
                    val hasModel = listOf("model.int8.onnx", "model.onnx").any { File(multiDir, it).isFile } &&
                        File(multiDir, "tokens.txt").isFile
                    if (hasModel) {
                        log("loading offline multilingual")
                        OfflineMultilingualAsr(multiDir.absolutePath)
                    } else {
                        log("offline multilingual missing")
                        null
                    }
                }.getOrElse {
                    log("offline multilingual failed: ${it.message}")
                    null
                }
                enAsr = runCatching {
                    val enDir = File(modelsRoot, AsrModelSpec.EN_STREAMING.dirName)
                    if (!enDir.isDirectory) enDir.mkdirs()
                    if (ensureModelFiles(enDir, AsrModelSpec.EN_STREAMING)) {
                        log("loading buffered EN ASR")
                        BufferedEnAsr(enDir.absolutePath, AsrModelSpec.EN_STREAMING)
                    } else {
                        log("EN model missing")
                        null
                    }
                }.getOrElse {
                    log("EN ASR failed: ${it.message}")
                    null
                }
                if (disposed) {
                    asrSession?.release()
                    offlineAsr?.close()
                    enAsr?.close()
                    return@launch
                }
                log(
                    "ASR loaded in ${SystemClock.elapsedRealtime() - loadStart}ms " +
                        "(multi=${offlineAsr != null}, en=${enAsr != null})",
                )
                // V2-08: single hybrid router; reuses resident RU Sherpa (no second model load).
                val ruRef = ru
                val hybrid = HybridAsrBridge(
                    app = app,
                    localSessionFactory = { ruRef },
                    localReady = { !disposed && asrSession != null },
                )
                hybridAsr = hybrid
                log("hybrid ASR ${hybrid.decisionLabel()}")

                // stop() обнуляет поля из другого потока, поэтому запуск держит свою ссылку.
                val capture = AudioCapture(app)
                audioCapture = capture
                if (!liveMode && wakeEnabled) {
                    VoiceIdentity.warm(app)
                    kwsSpotter = OwwWakeSpotter.tryCreate(
                        context = app,
                        sensitivityLevel = JarvisSettings.wakeSensitivity(app),
                    )
                    keywordSpotter = SherpaKeywordSpotter.tryCreate(
                        modelDir = ruDir.absolutePath,
                        keywordsFile = File(app.cacheDir, "wake_keywords.txt"),
                    )
                    if (kwsSpotter != null) {
                        // ASR только после wake — для распознавания команды, не для wake.
                        wakeWordEngine = WakeWordEngine(
                            session = ru,
                            scope = scope,
                            onPartial = ::publishPartial,
                            sensitivity = JarvisSettings.wakeSensitivity(app),
                            refineWithPcm = { pcm, streaming -> refineCommandPcm(pcm, streaming) },
                            voiceAllowed = ::commandVoiceAllowed,
                        )
                        log(
                            "wake KWS=${kwsSpotter?.engine} + sherpa=${keywordSpotter != null} " +
                                "(слово=джарвис, idle без ASR)",
                        )
                    } else {
                        log("wake KWS init failed — wake отключён (ASR-wake не используем)")
                    }
                }
                if (speaker == null) speaker = JarvisSpeaker(app)

                val continuous = liveMode || !wakeEnabled
                val commandEngine = wakeWordEngine

                if (!continuous && commandEngine != null) scope.launch {
                    commandEngine.wakeDetected.collect {
                        log("wake detected")
                        // Откликаемся, только когда точная проверка признала голос: на чужое
                        // «Джарвис» и шум — молчим. Слушать команду при этом уже начали.
                        if (!wakeVoiceConfirmed()) {
                            log("wake suppressed: second voice check")
                            wakeWordEngine?.cancelListening()
                            kwsSpotter?.armForNextWake(cooldownMs = 0)
                            keywordSpotter?.reset()
                            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.READY))
                            bargeWake = false
                            return@collect
                        }
                        JarvisEvents.publish(AssistantEvent.WakeDetected)
                        JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.LISTENING))
                        speaker?.onWakeDetected(chime = !bargeWake)
                        bargeWake = false
                        onWake()
                    }
                }

                if (!continuous && commandEngine != null) scope.launch {
                    commandEngine.userRequests.collect { req ->
                        if (commandEngine.state == WakeWordState.IDLE) {
                            kwsSpotter?.armForNextWake()
                            keywordSpotter?.reset()
                        }
                        if (suppressLocalWakeFinals || hybridAsr?.isTurnActive() == true) {
                            log("suppress local wake final (cloud turn active)")
                            return@collect
                        }
                        if (req.text.isBlank()) {
                            log("bare wake (no command) -> say listening")
                            speaker?.sayListening()
                            onRequest(req)
                        } else if (req.text.lowercase().trim() in SELF_ECHO) {
                            log("ignoring self-echo \"${req.text}\"")
                        } else {
                            log("req=\"${req.text}\"")
                            JarvisEvents.publish(AssistantEvent.RequestReceived(req.text))
                            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.THINKING))
                            onRequest(req)
                        }
                    }
                }

                capture.start(scope)
                scope.launch { watchMic() }
                scope.launch {
                    capture.chunks.collect { chunk ->
                        speechGate.onChunk(chunk.samples)
                        if (takeSample(chunk.samples)) return@collect
                        reportBargeWindow()
                        syncLiveTalk()
                        if (VoiceController.liveMuted && VoiceController.liveTalk) return@collect
                        if (disposed) return@collect
                        val sp = speaker ?: return@collect
                        // Глушим mic только под TTS-речь (не под wake-чим) + короткий хвост
                        // против самоподхвата. Раньше playing+400 мс резали команду после wake.
                        val talking = continuous || VoiceController.liveTalk
                        if (sp.speaking && !talking) {
                            handleBargeIn(chunk.samples)
                            return@collect
                        }
                        if (sp.speaking || SystemClock.elapsedRealtime() - sp.lastPlayEndAtMs < 220) {
                            return@collect
                        }
                        if (pauseCaptureForCloudAsr) {
                            // Android Speech owns mic; do not feed Sherpa/KWS.
                            return@collect
                        }
                        if (processingBusy) {
                            // Во время долгой задачи продолжаем слышать только слово активации:
                            // пользователь может отменить зависший ответ новым «Джарвис».
                            if (!talking && wakeWordEngine?.state == WakeWordState.IDLE) {
                                handleWakeChunk(chunk.samples)
                            }
                            return@collect
                        }
                        if (talking) {
                            handleContinuousChunk(chunk.samples)
                        } else {
                            handleWakeChunk(chunk.samples)
                        }
                    }
                }

                if (disposed) {
                    capture.stop()
                    asrSession?.release()
                    return@launch
                }
                val mode = when {
                    liveMode -> "live listening started (hybrid ASR)"
                    kwsSpotter != null -> "KWS listening (джарвис) + hybrid command ASR"
                    wakeEnabled && !liveMode -> "listening started (wake KWS unavailable)"
                    else -> "listening started (wake off, hybrid ASR)"
                }
                log(mode)
                JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.READY))
            } catch (e: Exception) {
                log("start failed: ${e.message}")
                JarvisEvents.publish(AssistantEvent.Error("VoiceEngine", e.message ?: "Voice start failed", e))
                JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.ERROR))
            }
        }
        return true
    }

    /** V2-08 continuous / live: HybridAsrController (local streaming or cloud). */
    private fun handleContinuousChunk(samples: FloatArray) {
        val bridge = hybridAsr ?: return
        if (!bridge.isTurnActive()) {
            val handoff = bridge.beginTurn(
                onPartial = { publishPartial(it) },
                onFinal = { text, tel -> deliverHybridFinal(text, tel, liveRefine = true) },
                onEndWithoutFinal = {
                    pauseCaptureForCloudAsr = false
                    if (audioCapture?.isRecording != true && !disposed) audioCapture?.start(scope)
                    log("continuous cloud ASR ended without final → mic restored")
                },
            )
            pauseCaptureForCloudAsr = handoff && bridge.isTurnActive()
            if (pauseCaptureForCloudAsr) {
                log("continuous cloud ASR mic handoff")
                audioCapture?.stop()
                return
            }
        }
        synchronized(livePcmLock) {
            if (livePcm.size < 16000 * 12) {
                for (s in samples) livePcm.add(s)
            }
        }
        bridge.feed(samples)
        // Whisper path: use resident Sherpa only as endpoint/VAD probe (text ignored).
        if (!pauseCaptureForCloudAsr &&
            bridge.preference() == AsrPreference.CLOUD_PREFERRED &&
            bridge.isTurnActive()
        ) {
            val probe = asrSession?.processChunk(samples)
            if (probe?.isEndpoint == true) {
                asrSession?.reset()
                bridge.finalizeTurn()
            }
        }
    }

    /** V2-08 wake path: KWS untouched; cloud command only when CLOUD_PREFERRED. */
    private var bargeOwwAt = 0L
    private var bargeKeywordAt = 0L
    private var bargePeak = 0f
    @Volatile private var bargeWake = false
    /** Фоновая проверка ERes2NetV2 последнего «Джарвис»: команда ждёт её итога. */
    @Volatile private var secondCheck: java.util.concurrent.CompletableFuture<VoiceSecondCheck.Result?>? = null
    @Volatile private var secondCheckAtMs = 0L

    private fun startSecondCheck(pcm: FloatArray, source: String) {
        val future = VoiceSecondCheck.checkAsync(app, pcm) ?: run {
            secondCheck = null
            return
        }
        secondCheck = future
        secondCheckAtMs = SystemClock.elapsedRealtime()
        future.thenAccept { r ->
            VoiceHealthLog.write(
                app,
                "check2 $source " + (r?.let { "${"%.2f".format(it.score)}/${"%.2f".format(VoiceSecondCheck.THRESHOLD)} imp=${it.impostor?.let { v -> "%.2f".format(v) } ?: "-"} ${it.ms}ms ok=${it.accepted}" } ?: "none"),
            )
        }
    }

    /**
     * Команда после «Джарвис» выполняется, если вторая проверка не признала голос чужим.
     * Обычно итог уже готов (команда длится дольше проверки); иначе ждём не дольше
     * [SECOND_CHECK_WAIT_MS] и, если не успела, пропускаем: не отвечать хозяину хуже.
     */
    private fun secondCheckAllows(): Boolean {
        val future = secondCheck ?: return true
        secondCheck = null
        if (SystemClock.elapsedRealtime() - secondCheckAtMs > SECOND_CHECK_FRESH_MS) return !VoiceSecondCheck.active(app)
        val result = runCatching {
            future.get(SECOND_CHECK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return !VoiceSecondCheck.active(app)
        lastSecondResult = result
        if (!result.accepted) VoiceHealthLog.write(app, "command rejected by check2 ${"%.2f".format(result.score)}")
        return result.accepted
    }

    /** Итог проверки последнего «Джарвис», к которому относится текущая команда. */
    @Volatile private var lastSecondResult: VoiceSecondCheck.Result? = null

    /**
     * Ждём точную проверку перед откликом на слово. Обычно она готова за ~0,3 с; дольше
     * [WAKE_CHECK_WAIT_MS] не ждём, а не успела — откликаемся (решит проверка команды).
     */
    private suspend fun wakeVoiceConfirmed(): Boolean {
        val future = secondCheck ?: return true
        if (SystemClock.elapsedRealtime() - secondCheckAtMs > SECOND_CHECK_FRESH_MS) return !VoiceSecondCheck.active(app)
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { future.get(WAKE_CHECK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        } ?: return !VoiceSecondCheck.active(app)
        return result.accepted
    }

    /**
     * Обучение второй проверки по итогу команды: команду после «Джарвис» отклонила проверка
     * голоса — слово было чужим; приняла, и слово уверенно своё — пополняем эталон.
     */
    private fun learnFromCommand(allowed: Boolean) {
        val result = lastSecondResult ?: return
        lastSecondResult = null
        learnFrom(result, allowed)
    }

    /**
     * «Джарвис», пауза, «Слушаю», команда: команда идёт как ожидаемая, но относится к тому же
     * слову. Её итог тоже учит вторую проверку, если слово прозвучало только что.
     */
    private fun learnFromFollowUp(allowed: Boolean) {
        val future = secondCheck ?: return
        if (SystemClock.elapsedRealtime() - secondCheckAtMs > OWNER_WAKE_TRUST_MS) return
        val result = future.getNow(null) ?: return
        secondCheck = null
        // Ожидаемая команда проверяется строже, и владельцу её иногда отклоняют. Чтобы его
        // голос не попал в чужие, учим «чужим» только слово, прошедшее впритык.
        if (!allowed && result.score >= VoiceSecondCheck.THRESHOLD + FOLLOW_UP_IMPOSTOR_MARGIN) return
        learnFrom(result, allowed)
    }

    private fun learnFrom(result: VoiceSecondCheck.Result, allowed: Boolean) {
        if (!allowed) {
            VoiceSecondCheck.learnImpostor(app, result)
            VoiceHealthLog.write(app, "check2 learn impostor ${"%.2f".format(result.score)}")
        } else if (result.accepted) {
            VoiceSecondCheck.learnOwner(app, result)
        }
    }

    /** Когда слово активации последний раз подтвердилось голосом владельца. */
    @Volatile private var ownerWakeAtMs = 0L
    private var wasSpeaking = false
    private val audioManager = app.getSystemService(android.media.AudioManager::class.java)
    /** Звук видео или музыки: подавление эха убирает его из микрофона, как и свою речь. */
    @Volatile private var mediaPlaying = false
    private var hushRequest: android.media.AudioFocusRequest? = null
    private var hushAtMs = 0L

    /**
     * Подавления эха мало: на время разговора видео должно замолчать. Приложения вроде YouTube
     * уступают звук сами, а TikTok нет, тогда пока мы слушаем, громкость убавлена.
     */
    private fun hushMedia() {
        if (hushRequest != null) return
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val result = audioManager?.requestAudioFocus(request)
        hushRequest = request
        hushAtMs = SystemClock.elapsedRealtime()
        VoiceHealthLog.write(app, "media hush result=$result")
    }

    private fun releaseHush() {
        restoreVolume()
        val request = hushRequest ?: return
        hushRequest = null
        audioManager?.abandonAudioFocusRequest(request)
        VoiceHealthLog.write(app, "media hush released")
    }

    private fun syncHush(speaking: Boolean) {
        val listening = wakeWordEngine?.state.let { it != null && it != WakeWordState.IDLE } ||
            hybridAsr?.isTurnActive() == true
        val now = SystemClock.elapsedRealtime()
        val settled = now - hushAtMs >= HUSH_DIP_DELAY_MS
        if (hushRequest != null && listening && !speaking && settled && mediaPlaying) dipVolume()
        if (!listening || speaking) restoreVolume()
        if (hushRequest == null || listening || speaking || processingBusy) return
        val playEnd = speaker?.lastPlayEndAtMs ?: 0L
        if (now - hushAtMs >= HUSH_MIN_MS && now - playEnd >= HUSH_AFTER_SPEECH_MS) releaseHush()
    }

    private fun dipVolume() {
        val am = audioManager ?: return
        val prefs = JarvisSettings.prefs(app)
        if (prefs.contains(HUSH_VOLUME_KEY)) return
        val current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val low = (am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * HUSH_VOLUME_SHARE).toInt().coerceAtLeast(1)
        if (current <= low) return
        // Запись на диск первой: если приложение упадёт, громкость вернётся при запуске.
        prefs.edit().putInt(HUSH_VOLUME_KEY, current).commit()
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, low, 0)
        VoiceHealthLog.write(app, "media volume dip $current -> $low")
    }

    private fun restoreVolume() {
        val prefs = JarvisSettings.prefs(app)
        if (!prefs.contains(HUSH_VOLUME_KEY)) return
        val saved = prefs.getInt(HUSH_VOLUME_KEY, 0)
        audioManager?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, saved, 0)
        prefs.edit().remove(HUSH_VOLUME_KEY).apply()
        VoiceHealthLog.write(app, "media volume restored $saved")
    }

    /** Микрофон с подавлением эха только на время своей речи; итог окна идёт в журнал для разбора. */
    private fun reportBargeWindow() {
        val speaking = speaker?.speaking == true
        // Паузы между кусками ответа не должны дёргать источник микрофона.
        val sp = speaker
        mediaPlaying = audioManager?.isMusicActive == true
        syncHush(speaking)
        audioCapture?.echoCancel = speaking || mediaPlaying ||
            (sp != null && SystemClock.elapsedRealtime() - sp.lastPlayEndAtMs < ECHO_HOLD_MS)
        if (!wasSpeaking && speaking) {
            // Проверка голоса при перебивании должна слушать только то, что звучало поверх ответа,
            // а не хвост только что сказанной команды: иначе голос владельца «подтверждался»
            // на его же команде, и ложное срабатывание обрывало ответ.
            recentAudio.clear()
            bargeOwwAt = 0L
            bargeKeywordAt = 0L
        }
        if (wasSpeaking && !speaking) {
            VoiceHealthLog.write(app, "speech end owwPeak=${"%.3f".format(bargePeak)}")
            bargePeak = 0f
        }
        wasSpeaking = speaking
    }

    /**
     * Прерывание голосом: «Джарвис» поверх собственной речи.
     * После голосовой команды детектор выключен, а взводится лишь вне речи: взводим его здесь.
     * Основной детектор (oww) на своей речи молчит, ему хватает проверки голоса как обычно.
     * Sherpa же срабатывает и на собственный голос Джарвиса из динамика, поэтому одному ему
     * верим, только если голос владельца подтвердился на звуке, записанном поверх ответа.
     * Когда Джарвис сам называет своё имя, нужны оба детектора.
     */
    private fun handleBargeIn(samples: FloatArray) {
        val kws = kwsSpotter ?: return
        val engine = wakeWordEngine ?: return
        if (engine.state != WakeWordState.IDLE) return
        if (!kws.isArmed) kws.armForNextWake(cooldownMs = 0)
        val now = SystemClock.elapsedRealtime()
        recentAudio.push(samples)
        if (kws.process(samples)) {
            bargeOwwAt = now
            kws.armForNextWake(cooldownMs = 0)
            VoiceHealthLog.write(app, "barge oww")
        }
        bargePeak = maxOf(bargePeak, kws.lastScoreValue)
        if (keywordSpotter?.process(samples) == true) {
            bargeKeywordAt = now
            VoiceHealthLog.write(app, "barge sherpa")
        }
        val oww = now - bargeOwwAt <= BARGE_WINDOW_MS
        val sherpa = now - bargeKeywordAt <= BARGE_WINDOW_MS
        val heard = if (speaker?.saysOwnName == true) oww && sherpa else oww || sherpa
        if (!heard || !speechGate.speechPresent()) return
        bargeOwwAt = 0L
        bargeKeywordAt = 0L
        val bargeAudio = recentAudio.snapshot()
        val voice = VoiceIdentity.check(app, bargeAudio)
        VoiceClips.save(app, "barge", bargeAudio, voice?.score, "oww=$oww sherpa=$sherpa accepted=${voice?.accepted}")
        if (!oww && voice?.accepted != true) {
            VoiceHealthLog.write(app, "barge rejected: sherpa only, voice ${voice?.let(::voiceLine) ?: "off"}")
            return
        }
        if (voice?.accepted == false) {
            VoiceHealthLog.write(app, "barge rejected: voice ${voiceLine(voice)}")
            bargeOwwAt = 0L
            bargeKeywordAt = 0L
            return
        }
        if (voice?.accepted == true) ownerWakeAtMs = now
        startSecondCheck(bargeAudio, "barge")
        VoiceHealthLog.write(app, "barge-in voice=${voice?.let(::voiceLine) ?: "off"}")
        bargeWake = true
        kws.disarm()
        keywordSpotter?.reset()
        engine.notifyExternalWake()
        maybeStartCloudCommandAfterWake()
    }

    private fun voiceLine(check: VoiceIdentity.Check) =
        "${"%.2f".format(check.score)}/${"%.2f".format(check.threshold)}"

    /**
     * Команду принимает только голос владельца. Если речи мало для сравнения, верим
     * команде сразу после слова активации, а в ожидании без него не верим: «да» из видео
     * не должно подтверждать действие.
     */
    private fun commandVoiceAllowed(pcm: FloatArray, awaitedRaw: Boolean): Boolean {
        // «Джарвис», пауза, «Слушаю», команда: команда идёт как ожидаемая (строгий порог),
        // хотя владелец только что подтвердил себя словом активации. Такую считаем командой
        // после слова, иначе Джарвис отказывал хозяину (0,31–0,42 при пороге 0,45).
        if (!awaitedRaw && !secondCheckAllows()) {
            log("command rejected: second voice check")
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.READY))
            return false
        }
        val awaited = awaitedRaw &&
            SystemClock.elapsedRealtime() - ownerWakeAtMs > OWNER_WAKE_TRUST_MS
        val check = VoiceIdentity.checkCommand(app, pcm, awaited, clean = !mediaPlaying)
        var allowed = check?.accepted ?: (!awaited || !VoiceIdentity.commandLocked(app))
        var strongOwnerCommand = false
        if (allowed && pcm.size >= MIN_SECOND_COMMAND_SAMPLES && VoiceSecondCheck.active(app)) {
            val second = VoiceSecondCheck.checkCommandAsync(app, pcm)?.let { future ->
                runCatching { future.get(SECOND_CHECK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
            }
            if (second?.accepted != true) {
                allowed = false
                log("command rejected by second voice check score=${second?.score ?: "unavailable"}")
            } else {
                strongOwnerCommand = second.score >= STRONG_COMMAND_LEARN_SCORE
            }
        }
        VoiceClips.save(app, "command", pcm, check?.score, "awaited=$awaited media=$mediaPlaying allowed=$allowed")
        // Погранично принятую команду исполняем, но не добавляем в эталон владельца.
        if (check != null && (!allowed || strongOwnerCommand)) {
            if (awaitedRaw) learnFromFollowUp(allowed) else learnFromCommand(allowed)
        } else {
            lastSecondResult = null
        }
        val score = check?.let { "${voiceLine(it)} ${"%.1f".format(it.seconds)}s" } ?: "none"
        VoiceHealthLog.write(app, "command voice=$score awaited=$awaited media=$mediaPlaying allowed=$allowed")
        if (!allowed) {
            log("command rejected: not the owner's voice")
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.READY))
        }
        return allowed
    }

    val isIdle: Boolean
        get() = speaker?.speaking != true && !processingBusy &&
            wakeWordEngine?.state == WakeWordState.IDLE && hybridAsr?.isTurnActive() != true

    /** Запись образца голоса: на это время детекторы и команды не получают звук. */
    fun recordSample(durationMs: Int, done: (FloatArray) -> Unit) {
        sampleTake = SampleTake(16 * durationMs, done)
    }

    private fun takeSample(samples: FloatArray): Boolean {
        val take = sampleTake ?: return false
        if (take.add(samples)) {
            sampleTake = null
            take.done(take.pcm)
        }
        return true
    }

    private class SampleTake(size: Int, val done: (FloatArray) -> Unit) {
        val pcm = FloatArray(size)
        private var filled = 0

        fun add(samples: FloatArray): Boolean {
            val count = minOf(samples.size, pcm.size - filled)
            samples.copyInto(pcm, filled, 0, count)
            filled += count
            return filled == pcm.size
        }
    }

    private var liveTalkApplied = false

    /** Live без перезагрузки моделей: слово активации на это время выключено. */
    private fun syncLiveTalk() {
        val want = VoiceController.liveTalk
        if (want == liveTalkApplied) return
        liveTalkApplied = want
        if (want) {
            kwsSpotter?.disarm()
            keywordSpotter?.reset()
            wakeWordEngine?.cancelListening()
            log("live talk on")
        } else {
            hybridAsr?.cancelTurn()
            kwsSpotter?.armForNextWake(cooldownMs = 0)
            keywordSpotter?.reset()
            log("live talk off")
        }
    }

    /** Кнопка микрофона: то же, что сказать слово активации, в том числе поверх речи. */
    fun manualWake() {
        val engine = wakeWordEngine ?: return
        bargeWake = speaker?.speaking == true
        if (!bargeWake && audioManager?.isMusicActive == true) hushMedia()
        kwsSpotter?.disarm()
        keywordSpotter?.reset()
        engine.notifyExternalWake()
        maybeStartCloudCommandAfterWake()
    }

    /** Отмена запроса кнопкой: перестать слушать и вернуться к ожиданию слова. */
    fun cancelTurn() {
        wakeWordEngine?.cancelListening()
        kwsSpotter?.armForNextWake(cooldownMs = 0)
        keywordSpotter?.reset()
        stopSpeaking()
    }

    private fun handleWakeChunk(samples: FloatArray) {
        if (WakeAudioDump.isActive) {
            WakeAudioDump.write(samples)
            return
        }
        if (WakeAudioDump.takeReplayRequest()) {
            replayDump()
            return
        }
        val kws = kwsSpotter ?: return
        val engine = wakeWordEngine ?: return
        val bridge = hybridAsr
        val before = engine.state

        if (bridge != null && bridge.isTurnActive()) {
            synchronized(livePcmLock) {
                if (livePcm.size < 16000 * 12) for (s in samples) livePcm.add(s)
            }
            bridge.feed(samples)
            if (!pauseCaptureForCloudAsr) {
                val probe = asrSession?.processChunk(samples)
                if (probe?.isEndpoint == true) {
                    asrSession?.reset()
                    bridge.finalizeTurn()
                }
            }
            return
        }

        if (before == WakeWordState.IDLE) {
            // Хвост голоса ассистента не должен будить детекторы: они слышат его с задержкой.
            val sp = speaker
            if (sp != null && SystemClock.elapsedRealtime() - sp.lastPlayEndAtMs < WAKE_HOLDOFF_AFTER_SPEECH_MS) {
                keywordSpotter?.reset()
                return
            }
            wakeFedAtMs = SystemClock.elapsedRealtime()
            recentAudio.push(samples)
            val owwHit = kws.process(samples)
            val keywordHit = keywordSpotter?.process(samples) == true
            if (owwHit || keywordHit) {
                if (!speechGate.speechPresent()) {
                    VoiceHealthLog.write(
                        app,
                        "wake rejected: silence oww=$owwHit sherpa=$keywordHit " +
                            "voiced=${speechGate.voicedChunks()} floor=${"%.5f".format(speechGate.noiseFloor)}",
                    )
                    kws.armForNextWake(cooldownMs = 0)
                    keywordSpotter?.reset()
                    return
                }
                val wakeAudio = recentAudio.snapshot()
                val voice = VoiceIdentity.check(
                    app,
                    wakeAudio,
                    clean = !mediaPlaying,
                    media = mediaPlaying,
                )
                VoiceClips.save(
                    app,
                    if (voice?.accepted == false) "wake_rejected" else "wake",
                    wakeAudio,
                    voice?.score,
                    "media=$mediaPlaying oww=$owwHit sherpa=$keywordHit",
                )
                if (voice?.accepted == false) {
                    VoiceHealthLog.write(app, "wake rejected: voice ${voiceLine(voice)} media=$mediaPlaying")
                    kws.armForNextWake()
                    keywordSpotter?.reset()
                    return
                }
                if (voice?.accepted == true) ownerWakeAtMs = SystemClock.elapsedRealtime()
                startSecondCheck(wakeAudio, "wake")
                VoiceHealthLog.write(
                    app,
                    "wake hit voice=${voice?.let(::voiceLine) ?: "off"} oww=$owwHit sherpa=$keywordHit score=${"%.3f".format(kws.lastScoreValue)} " +
                        "voiced=${speechGate.voicedChunks()} floor=${"%.5f".format(speechGate.noiseFloor)}",
                )
                log("KWS джарвис hit oww=$owwHit sherpa=$keywordHit → listen command (KWS off)")
                if (mediaPlaying) hushMedia()
                kws.disarm()
                keywordSpotter?.reset()
                engine.notifyExternalWake()
                maybeStartCloudCommandAfterWake()
            }
        } else {
            if (bridge?.isTurnActive() != true) {
                engine.onAudioChunk(samples)
            }
            if (engine.state == WakeWordState.IDLE && before != WakeWordState.IDLE) {
                kws.armForNextWake()
                keywordSpotter?.reset()
                suppressLocalWakeFinals = false
                log("session done → KWS re-armed")
            }
        }
    }

    /** Debug: прогон записанного голоса через оба детектора, с разметкой по времени записи. */
    private fun replayDump() {
        val kws = kwsSpotter ?: return
        val chunk = AudioCapture.CHUNK_SAMPLES
        val pcm = WakeAudioDump.read(app)
        val started = SystemClock.elapsedRealtime()
        kws.armForNextWake(cooldownMs = 0)
        keywordSpotter?.reset()
        var pos = 0
        var cooldownUntil = 0
        while (pos + chunk <= pcm.size) {
            val samples = pcm.copyOfRange(pos, pos + chunk)
            val oww = kws.process(samples)
            val sherpa = keywordSpotter?.process(samples) == true
            if (oww || sherpa) {
                log("replay hit t=${"%.1f".format(pos / 16000f)} oww=$oww sherpa=$sherpa")
                kws.armForNextWake(cooldownMs = 0)
            }
            pos += chunk
        }
        log("replay done seconds=${pcm.size / 16000} tookMs=${SystemClock.elapsedRealtime() - started}")
        kws.armForNextWake()
    }

    /** Сторож: отдельный таймер, потому что глухой микрофон не присылает чанков. */
    private suspend fun watchMic() {
        var lastBeat = 0L
        while (!disposed) {
            delay(MIC_WATCH_PERIOD_MS)
            val capture = audioCapture ?: continue
            if (pauseCaptureForCloudAsr) continue
            val now = SystemClock.elapsedRealtime()
            val soundAgo = now - capture.lastSoundAtMs
            val chunkAgo = now - capture.lastChunkAtMs
            if (now - lastBeat >= MIC_HEARTBEAT_MS) {
                lastBeat = now
                VoiceHealthLog.write(
                    app,
                    "beat soundAgo=$soundAgo chunkAgo=$chunkAgo silenced=${capture.clientSilenced} " +
                        "readErrors=${capture.readErrors} recording=${capture.isRecording} busy=$processingBusy " +
                        "speaking=${speaker?.speaking} state=${wakeWordEngine?.state} armed=${kwsSpotter?.isArmed} " +
                        "wakeFedAgo=${now - wakeFedAtMs} restarts=$micRestarts",
                )
            }
            val deaf = soundAgo >= MIC_DEAF_MS || chunkAgo >= MIC_DEAF_MS || capture.clientSilenced
            if (deaf && now - micRestartAtMs >= MIC_RESTART_GAP_MS) {
                micRestartAtMs = now
                micRestarts++
                VoiceHealthLog.write(
                    app,
                    "mic deaf soundAgo=$soundAgo chunkAgo=$chunkAgo silenced=${capture.clientSilenced} " +
                        "readErrors=${capture.readErrors} → restart #$micRestarts",
                )
                capture.stop()
                capture.start(scope)
            }
        }
    }

    private fun maybeStartCloudCommandAfterWake() {
        val bridge = hybridAsr ?: return
        if (bridge.preference() != AsrPreference.CLOUD_PREFERRED) return
        val ctx = bridge.routeContext()
        if (ctx.privacyRestricted || !ctx.isOnline || !ctx.cloudHealthy) return
        suppressLocalWakeFinals = true
        val handoff = bridge.beginTurn(
            onPartial = { publishPartial(it) },
            onFinal = { text, tel ->
                pauseCaptureForCloudAsr = false
                suppressLocalWakeFinals = false
                wakeWordEngine?.cancelListening()
                if (audioCapture?.isRecording != true && !disposed) {
                    audioCapture?.start(scope)
                }
                deliverHybridFinal(text, tel, liveRefine = false)
                kwsSpotter?.armForNextWake()
                keywordSpotter?.reset()
                log("cloud command done → KWS re-armed")
            },
            onEndWithoutFinal = {
                pauseCaptureForCloudAsr = false
                suppressLocalWakeFinals = false
                wakeWordEngine?.cancelListening()
                kwsSpotter?.armForNextWake()
                keywordSpotter?.reset()
                if (audioCapture?.isRecording != true && !disposed) audioCapture?.start(scope)
                log("cloud command ended without final → wake restored")
            },
        )
        pauseCaptureForCloudAsr = handoff && bridge.isTurnActive()
        if (pauseCaptureForCloudAsr) {
            audioCapture?.stop()
        }
        log("wake→cloud command handoff=$handoff ${bridge.decisionLabel()}")
    }

    private fun deliverHybridFinal(text: String, tel: com.jarvis.core.voice.AsrTelemetry, liveRefine: Boolean) {
        scope.launch {
            var accept = true
            hybridFinalMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHybridFinalAtMs < 400) {
                    accept = false
                } else {
                    lastHybridFinalAtMs = now
                }
            }
            if (!accept) {
                log("hybrid final dedupe skip")
                return@launch
            }
            var out = text.trim()
            val heard = synchronized(livePcmLock) { FloatArray(livePcm.size) { i -> livePcm[i] } }
            if (out.isNotBlank() && heard.isNotEmpty() && !commandVoiceAllowed(heard, awaitedRaw = liveRefine)) {
                synchronized(livePcmLock) { livePcm.clear() }
                pendingLivePrefix = ""
                return@launch
            }
            if (liveRefine && tel.asrRoute == "local") {
                val pcmSnapshot = synchronized(livePcmLock) {
                    if (livePcm.isEmpty()) null
                    else FloatArray(livePcm.size) { i -> livePcm[i] }
                }
                if (pcmSnapshot != null) {
                val streaming = listOf(pendingLivePrefix, out).filter { it.isNotBlank() }.joinToString(" ")
                val refined = refineCommandPcm(pcmSnapshot, streaming)
                out = CommandTextSelector.select(
                    streamingRu = streaming,
                    offlineMulti = refined?.multilingual,
                    englishPass = refined?.english,
                )
                if (com.jarvis.core.nlp.AsrPhraseNormalizer.isIncompleteCommand(out)) {
                    pendingLivePrefix = out
                    log("incomplete live utterance \"$out\" — wait for more")
                    synchronized(livePcmLock) { livePcm.clear() }
                    hybridAsr?.beginTurn(
                        onPartial = { publishPartial(it) },
                        onFinal = { t, te -> deliverHybridFinal(t, te, liveRefine = true) },
                    )
                    return@launch
                }
                pendingLivePrefix = ""
                }
            }
            synchronized(livePcmLock) { livePcm.clear() }
            pauseCaptureForCloudAsr = false
            if (audioCapture?.isRecording != true && !disposed) {
                audioCapture?.start(scope)
            }
            log("hybrid final provider=${tel.asrProvider} route=${tel.asrRoute} reason=${tel.asrRouteReason} \"$out\"")
            android.util.Log.i("Jarvis.Asr", tel.summary())
            if (out.isBlank()) return@launch
            val request = UserRequest(text = out)
            JarvisEvents.publish(AssistantEvent.RequestReceived(request.text))
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.THINKING))
            onRequest(request)
        }
    }

    fun stop() {
        releaseHush()
        log("stopping")
        disposed = true
        hybridAsr?.cancelTurn()
        hybridAsr = null
        pauseCaptureForCloudAsr = false
        suppressLocalWakeFinals = false
        audioCapture?.stop()
        audioCapture = null
        asrSession?.release()
        asrSession = null
        offlineAsr?.close()
        offlineAsr = null
        enAsr?.close()
        enAsr = null
        wakeWordEngine = null
        kwsSpotter?.close()
        kwsSpotter = null
        keywordSpotter?.close()
        keywordSpotter = null
        synchronized(livePcmLock) { livePcm.clear() }
        pendingLivePrefix = ""
        speaker?.release()
        speaker = null
        hideOverlay()
        scope.cancel()
        log("stopped")
        JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.OFFLINE))
    }

    /** V2-06: cancel in-flight TTS without tearing down the engine. */
    fun stopSpeaking() {
        speaker?.stop()
    }

    /** true, пока Jarvis реально произносит текст (для проактивной доставки). */
    val isSpeaking: Boolean
        get() = speaker?.isSpeaking() == true

    /** V2-07: begin queued sentence TTS for LLM stream. */
    fun beginStreamSpeak(): Int = speaker?.beginStreamSpeak() ?: 0

    fun enqueueStreamSpeak(
        text: String,
        streamGeneration: Int,
        onFirstAudio: ((Long) -> Unit)? = null,
        hint: com.jarvis.core.nlp.CinemaPhrases.Hint? = null,
    ) {
        speaker?.enqueueSpeak(text, streamGeneration, onFirstAudio, hint = hint)
    }

    fun endStreamSpeak(onComplete: () -> Unit = {}) {
        speaker?.endStreamSpeak(onComplete)
            ?: onComplete()
    }

    fun speak(
        text: String,
        onComplete: (() -> Unit)? = null,
        onFirstAudio: ((Long) -> Unit)? = null,
        hint: com.jarvis.core.nlp.CinemaPhrases.Hint? = null,
    ) {
        scope.launch {
            var ready = speaker
            var waited = 0
            while (ready == null && !disposed && waited < 40) {
                kotlinx.coroutines.delay(100)
                ready = speaker
                waited++
            }
            val sp = ready
            if (sp == null) {
                log("speak skipped: TTS not ready")
                onFirstAudio?.invoke(0L)
                onComplete?.invoke()
                return@launch
            }
            sp.speakResponse(
                text,
                onComplete = {
                    JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.READY))
                    onComplete?.invoke()
                },
                onFirstAudio = onFirstAudio,
                hint = hint,
            )
        }
    }

    /** Слушать follow-up без wake: подтверждение, уточнение или продолжение разговора. */
    fun listenForFollowUp(timeoutMs: Long) {
        wakeWordEngine?.listenForFollowUp(timeoutMs)
    }

    /** Плавающий орб — OverlayOrbService (ядро + мини-меню). Старый CoreView не поднимаем. */
    fun showOverlay() {
        OverlayOrbService.show(app)
    }

    fun hideOverlay() {
        overlayScope?.cancel()
        overlayScope = null
        overlayView?.let {
            overlayView = null
            runCatching { app.getSystemService(android.view.WindowManager::class.java).removeView(it) }
        }
    }

    private fun publishPartial(partial: String) {
        if (partial.isBlank()) return
        log("partial \"$partial\"")
        JarvisEvents.publish(AssistantEvent.TranscriptUpdated(partial))
        onPartial(partial)
    }

    /**
     * Быстрый refine: для простых RU-команд без EN — пропускаем (экономия сотен мс).
     * Для music/open: NeMo(full) + при необходимости EN/NeMo(tail).
     */
    private fun refineCommandPcm(pcm: FloatArray, streamingRu: String = ""): CommandRefinePasses? {
        if (pcm.isEmpty()) return null
        val t0 = SystemClock.elapsedRealtime()
        if (streamingRu.isBlank()) {
            // Обычные RU-команды не замедляем. Когда RU вообще ничего не услышала,
            // пробуем уже загруженную EN-модель на той же записи.
            val english = enAsr?.let { eng ->
                runCatching { eng.decode(CommandPcmRefiner.tailOf(pcm, 16_000 * 6)) }.getOrNull()
            }
            val hint = CommandPcmRefiner.bestLatinHint(english)
            val usable = hint.split(' ').count { it.isNotBlank() } >= 2
            log("refine empty-RU ${SystemClock.elapsedRealtime() - t0}ms en=$usable")
            return hint.takeIf { usable }?.let { CommandRefinePasses(english = it) }
        }
        if (!CommandPcmRefiner.needsLatinRefine(streamingRu)) {
            log("refine skip ${SystemClock.elapsedRealtime() - t0}ms (no EN needed)")
            return null
        }
        val multiFull = offlineAsr?.let { off ->
            runCatching { off.decode(pcm) }.getOrElse {
                log("multi decode failed: ${it.message}")
                null
            }
        }
        var enHint = ""
        var multiTail: String? = null
        var enTail: String? = null
        var path = "multi"

        if (CommandPcmRefiner.hasUsefulLatin(multiFull)) {
            enHint = multiFull.orEmpty()
            path = "multi-latin"
        } else {
            val tail = CommandPcmRefiner.tailOf(pcm)
            val enJob = enAsr?.let { eng ->
                java.util.concurrent.CompletableFuture.supplyAsync {
                    runCatching { eng.decode(tail) }.getOrNull()
                }
            }
            multiTail = if (tail.size < pcm.size) {
                offlineAsr?.let { off ->
                    runCatching { off.decode(tail) }.getOrNull()
                }
            } else {
                null
            }
            enTail = enJob?.get()
            enHint = CommandPcmRefiner.bestLatinHint(enTail, multiTail)
            path = "multi+tail"
        }

        val ms = SystemClock.elapsedRealtime() - t0
        log(
            "refine ${ms}ms path=$path pcm=${pcm.size} " +
                "multi=\"${multiFull?.take(60)}\" " +
                "multiTail=\"${multiTail?.take(30)}\" " +
                "enTail=\"${enTail?.take(30)}\" hint=\"${enHint.take(40)}\"",
        )
        if (multiFull.isNullOrBlank() && enHint.isBlank()) return null
        return CommandRefinePasses(
            multilingual = multiFull,
            english = enHint.ifBlank { null },
        )
    }

    private fun ensureModelFiles(modelDir: File, spec: AsrModelSpec): Boolean {
        val required = spec.requiredFiles()
        if (required.all { File(modelDir, it).exists() }) return true
        val assetPath = "models/${spec.dirName}"
        val assets = runCatching { app.assets.list(assetPath) ?: emptyArray() }.getOrDefault(emptyArray())
        if (assets.isEmpty()) return required.all { File(modelDir, it).exists() }
        assets.forEach { name ->
            runCatching {
                app.assets.open("$assetPath/$name").use { input ->
                    File(modelDir, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return required.all { File(modelDir, it).exists() }
    }

    private fun log(msg: String) = Log.d(TAG, "voice[$sessionId]: $msg")

    companion object {
        private const val TAG = "Jarvis.Debug"
        private const val BARGE_WINDOW_MS = 1_000L
        private const val OWNER_WAKE_TRUST_MS = 10_000L
        private const val SECOND_CHECK_WAIT_MS = 1_500L
        private const val MIN_SECOND_COMMAND_SAMPLES = 16_000 * 8 / 10
        private const val STRONG_COMMAND_LEARN_SCORE = 0.55f
        // Первый запрос к ERes2NetV2 после запуска может занять около 0,9 с:
        // прежние 700 мс отклоняли владельца до положительного результата.
        private const val WAKE_CHECK_WAIT_MS = 1_500L
        private const val FOLLOW_UP_IMPOSTOR_MARGIN = 0.05f
        private const val SECOND_CHECK_FRESH_MS = 20_000L
        private const val ECHO_HOLD_MS = 700L
        private const val HUSH_MIN_MS = 1_500L
        private const val HUSH_AFTER_SPEECH_MS = 800L
        private const val HUSH_DIP_DELAY_MS = 300L
        private const val HUSH_VOLUME_SHARE = 0.15f
        private const val HUSH_VOLUME_KEY = "hush_saved_volume"
        private const val WAKE_HOLDOFF_AFTER_SPEECH_MS = 1_500L
        /** 1.5 с при 16 кГц: слово и немного до него. */
        private const val VOICE_CHECK_SAMPLES = 24_000
        private const val MIC_WATCH_PERIOD_MS = 2_000L
        private const val MIC_HEARTBEAT_MS = 60_000L
        private const val MIC_DEAF_MS = 5_000L
        private const val MIC_RESTART_GAP_MS = 30_000L
        private var sessionCounter = 0
        val SELF_ECHO = setOf("слушаю сэр", "слушаю, сэр", "слушаю")
    }
}

/** Процесс-wide единая точка запуска голоса через foreground-сервис.
 *
 * Stage 4.5 contract — **один VoicePipeline**, несколько входов:
 * - UI / Dashboard → [start] / [startLive]
 * - системный помощник (VIS / ROLE_ASSISTANT) → [startFromSystemAssist]
 * - debug → [start]
 *
 * Владелец lifecycle микрофона/ASR/TTS: только [VoiceService] + [VoiceEngine].
 * [com.jarvis.app.assist.JarvisVoiceInteractionService] — тонкий вход, не второй pipeline.
 */
object VoiceController {

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isLiveSession: Boolean = false
        private set

    /** true, если VoiceService жив и TTS-движок присоединён (можно говорить). */
    val hasEngine: Boolean
        get() = activeEngine != null

    /** true, если Jarvis сейчас произносит текст. */
    val isSpeaking: Boolean
        get() = activeEngine?.isSpeaking == true

    /** Откуда последний раз подняли VoiceService (diagnostics). */
    @Volatile
    var lastEntry: String = ENTRY_UNKNOWN
        private set

    @Volatile
    private var activeEngine: VoiceEngine? = null

    /** Сменить голос на выбранный в настройках, не перезапуская слух. */
    fun reloadVoice(onReady: (() -> Unit)? = null) {
        activeEngine?.reloadVoice(onReady)
    }

    interface Listener {
        fun onWake()
        fun onRequest(request: UserRequest)
        fun onPartial(text: String) {}
    }

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Разговор Live без слова активации; движок подхватывает это на следующем куске звука. */
    @Volatile var liveTalk: Boolean = false
    @Volatile var liveMuted: Boolean = false
    @Volatile var liveStartedAt: Long = 0L

    fun manualWake() {
        activeEngine?.manualWake()
    }

    /** Джарвис не говорит, не думает и не ждёт команду. */
    val isIdle: Boolean get() = activeEngine?.isIdle == true

    /** false, если ассистент выключен и микрофон никто не слушает. */
    fun recordVoiceSample(durationMs: Int, done: (FloatArray) -> Unit): Boolean {
        val engine = activeEngine ?: return false
        engine.recordSample(durationMs, done)
        return true
    }

    fun cancelTurn(context: Context) {
        activeEngine?.cancelTurn()
        BrainGraph.build(context).cancelActiveChatStream()
    }

    fun start(context: Context) {
        startInternal(context, liveMode = false, entry = ENTRY_UI)
    }

    fun startLive(context: Context) {
        startInternal(context, liveMode = true, entry = ENTRY_UI_LIVE)
    }

    /**
     * Вход системного цифрового помощника (VIS / Assist).
     * Не создаёт второй pipeline — тот же [VoiceService].
     */
    fun startFromSystemAssist(context: Context) {
        startInternal(context, liveMode = true, entry = ENTRY_SYSTEM_ASSIST)
    }

    @Volatile private var appContextForWidget: Context? = null

    private fun startInternal(context: Context, liveMode: Boolean, entry: String) {
        appContextForWidget = context.applicationContext
        lastEntry = entry
        JarvisSettings.setVoiceWanted(context, true)
        if (liveMode) isLiveSession = true
        val action = if (liveMode) VoiceService.ACTION_LIVE_START else VoiceService.ACTION_START
        Log.i(TAG, "pipeline start entry=$entry live=$liveMode running=$isRunning")
        // Из фона Android 12+ не даёт запускать службу с микрофоном: не роняем вызывающий процесс.
        runCatching {
            context.startForegroundService(
                Intent(context, VoiceService::class.java).apply {
                    this.action = action
                    putExtra(VoiceService.EXTRA_ENTRY, entry)
                    putExtra(VoiceService.EXTRA_FOREGROUND, true)
                },
            )
        }.onFailure { e ->
            Log.w(TAG, "voice service start refused: ${e.message}")
            CrashReports.record(context, "запуск голоса отклонён системой (вход: $entry)", e)
        }
    }

    fun stop(context: Context) {
        JarvisSettings.setVoiceWanted(context, false)
        runCatching {
            context.startService(
                Intent(context, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP }
            )
        }
    }

    fun speak(
        text: String,
        onComplete: (() -> Unit)? = null,
        onFirstAudio: ((Long) -> Unit)? = null,
        hint: com.jarvis.core.nlp.CinemaPhrases.Hint? = null,
    ) {
        activeEngine?.speak(text, onComplete, onFirstAudio, hint) ?: run {
            onFirstAudio?.invoke(0L)
            onComplete?.invoke()
        }
        JarvisEvents.publish(AssistantEvent.ResponseGenerated(text))
        JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.SPEAKING))
    }

    /** V2-06: stop TTS for barge-in / new request / cancellation. */
    fun stopSpeaking() {
        activeEngine?.stopSpeaking()
    }

    fun beginStreamSpeak(): Int = activeEngine?.beginStreamSpeak() ?: 0

    fun enqueueStreamSpeak(
        text: String,
        streamGeneration: Int,
        onFirstAudio: ((Long) -> Unit)? = null,
        hint: com.jarvis.core.nlp.CinemaPhrases.Hint? = null,
    ) {
        activeEngine?.enqueueStreamSpeak(text, streamGeneration, onFirstAudio, hint)
    }

    fun endStreamSpeak(onComplete: () -> Unit = {}) {
        activeEngine?.endStreamSpeak(onComplete) ?: onComplete()
    }

    fun listenForFollowUp(timeoutMs: Long = com.jarvis.core.nlp.ConversationWindow.CONFIRMATION_MS) {
        activeEngine?.listenForFollowUp(timeoutMs)
    }

    /** Пауза ASR на время Brain/LLM (иначе tok/s падает из‑за конкуренции CPU). */
    fun setProcessingBusy(busy: Boolean) {
        activeEngine?.processingBusy = busy
    }

    fun showOverlay() {
        activeEngine?.showOverlay()
    }

    fun hideOverlay() {
        activeEngine?.hideOverlay()
    }

    internal fun notifyWake() {
        mainHandler.post { listeners.forEach { it.onWake() } }
    }

    internal fun notifyPartial(text: String) {
        mainHandler.post { listeners.forEach { it.onPartial(text) } }
    }

    internal fun notifyRequest(request: UserRequest) {
        mainHandler.post { listeners.forEach { it.onRequest(request) } }
    }

    internal fun setRunning(running: Boolean) {
        isRunning = running
        appContextForWidget?.let { JarvisWidgetProvider.refresh(it) }
        if (!running) {
            activeEngine = null
            isLiveSession = false
        }
    }

    internal fun attachEngine(engine: VoiceEngine) {
        activeEngine = engine
    }

    const val ENTRY_UNKNOWN = "unknown"
    const val ENTRY_UI = "ui"
    const val ENTRY_UI_LIVE = "ui_live"
    const val ENTRY_SYSTEM_ASSIST = "system_assist"
    const val ENTRY_DEBUG = "debug"

    private const val TAG = "Jarvis.VoicePipeline"
}
