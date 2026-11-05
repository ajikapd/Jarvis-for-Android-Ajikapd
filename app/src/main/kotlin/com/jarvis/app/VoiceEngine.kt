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
    private var micRestartAtMs = 0L
    private var micRestarts = 0
    private var speaker: JarvisSpeaker? = null
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
                hybridAsr = HybridAsrBridge(
                    app = app,
                    localSessionFactory = { ruRef },
                    localReady = { !disposed && asrSession != null },
                )
                log("hybrid ASR ${hybridAsr!!.decisionLabel()}")

                audioCapture = AudioCapture(app)
                if (!liveMode && wakeEnabled) {
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
                        )
                        log(
                            "wake KWS=${kwsSpotter!!.engine} + sherpa=${keywordSpotter != null} " +
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
                    var lastWakeAtMs = 0L
                    commandEngine.wakeDetected.collect {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastWakeAtMs < 1000) return@collect
                        lastWakeAtMs = now
                        log("wake detected")
                        JarvisEvents.publish(AssistantEvent.WakeDetected)
                        JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.LISTENING))
                        speaker?.onWakeDetected()
                        onWake()
                    }
                }

                if (!continuous && commandEngine != null) scope.launch {
                    commandEngine.userRequests.collect { req ->
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

                audioCapture!!.start(scope)
                scope.launch { watchMic() }
                scope.launch {
                    audioCapture!!.chunks.collect { chunk ->
                        speechGate.onChunk(chunk.samples)
                        if (processingBusy || disposed) return@collect
                        val sp = speaker ?: return@collect
                        // Глушим mic только под TTS-речь (не под wake-чим) + короткий хвост
                        // против самоподхвата. Раньше playing+400 мс резали команду после wake.
                        if (sp.speaking || SystemClock.elapsedRealtime() - sp.lastPlayEndAtMs < 220) {
                            return@collect
                        }
                        if (pauseCaptureForCloudAsr) {
                            // Android Speech owns mic; do not feed Sherpa/KWS.
                            return@collect
                        }
                        if (continuous) {
                            handleContinuousChunk(chunk.samples)
                        } else {
                            handleWakeChunk(chunk.samples)
                        }
                    }
                }

                if (disposed) {
                    audioCapture!!.stop()
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
            )
            pauseCaptureForCloudAsr = handoff
            if (handoff) {
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
                VoiceHealthLog.write(
                    app,
                    "wake hit oww=$owwHit sherpa=$keywordHit score=${"%.3f".format(kws.lastScoreValue)} " +
                        "voiced=${speechGate.voicedChunks()} floor=${"%.5f".format(speechGate.noiseFloor)}",
                )
                log("KWS джарвис hit oww=$owwHit sherpa=$keywordHit → listen command (KWS off)")
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
                        "restarts=$micRestarts",
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
                if (audioCapture?.isRecording != true && !disposed) {
                    audioCapture?.start(scope)
                }
                deliverHybridFinal(text, tel, liveRefine = false)
                kwsSpotter?.armForNextWake()
                log("cloud command done → KWS re-armed")
            },
        )
        pauseCaptureForCloudAsr = handoff
        if (handoff) {
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
    ) {
        speaker?.enqueueSpeak(text, streamGeneration, onFirstAudio)
    }

    fun endStreamSpeak(onComplete: () -> Unit = {}) {
        speaker?.endStreamSpeak(onComplete)
            ?: onComplete()
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null, onFirstAudio: ((Long) -> Unit)? = null) {
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
            )
        }
    }

    /** Слушать follow-up без wake (подтверждение / уточнение). */
    fun listenForFollowUp() {
        wakeWordEngine?.listenForFollowUp(10_000L)
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
        private const val WAKE_HOLDOFF_AFTER_SPEECH_MS = 1_500L
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

    private fun startInternal(context: Context, liveMode: Boolean, entry: String) {
        lastEntry = entry
        if (liveMode) isLiveSession = true
        val action = if (liveMode) VoiceService.ACTION_LIVE_START else VoiceService.ACTION_START
        Log.i(TAG, "pipeline start entry=$entry live=$liveMode running=$isRunning")
        context.startForegroundService(
            Intent(context, VoiceService::class.java).apply {
                this.action = action
                putExtra(VoiceService.EXTRA_ENTRY, entry)
            },
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP }
        )
    }

    fun speak(
        text: String,
        onComplete: (() -> Unit)? = null,
        onFirstAudio: ((Long) -> Unit)? = null,
    ) {
        activeEngine?.speak(text, onComplete, onFirstAudio) ?: run {
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
    ) {
        activeEngine?.enqueueStreamSpeak(text, streamGeneration, onFirstAudio)
    }

    fun endStreamSpeak(onComplete: () -> Unit = {}) {
        activeEngine?.endStreamSpeak(onComplete) ?: onComplete()
    }

    fun listenForFollowUp() {
        activeEngine?.listenForFollowUp()
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