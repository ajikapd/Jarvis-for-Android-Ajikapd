package com.jarvis.app

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.jarvis.app.tts.HybridTtsBridge
import com.jarvis.core.voice.TtsTelemetry
import com.jarvis.tts.JarvisTts
import com.jarvis.tts.JarvisTtsConfig
import com.jarvis.tts.TtsPlayer
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hybrid TTS playback owner (V2-09).
 * Local Piper + optional cloud via HybridTtsBridge; single [TtsPlayer].
 * V2-07 sentence queue preserved: beginStreamSpeak / enqueueSpeak / endStreamSpeak.
 */
class JarvisSpeaker(context: Context) {

    companion object {
        const val TTS_MODEL_DIR = "vits-piper-ru_RU-dmitri-medium"
        private const val TTS_MODEL_DIR_INT8 = "vits-piper-ru_RU-dmitri-medium-int8"
        private const val TAG = "Jarvis.Speaker"
        /** Большинство ответов целиком — один гладкий play. */
        private const val FULL_SYNTH_MAX_CHARS = 160
        /** Bound stream queue depth (sentence chunks). */
        private const val MAX_QUEUE_DEPTH = 12
    }

    private val appCtx = context.applicationContext
    private val ttsDir: File = resolveTtsDir(context)
    private val player = TtsPlayer()
    private val ttsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-tts").apply { priority = Thread.MAX_PRIORITY }
    }
    private val ttsDispatcher = ttsExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ttsDispatcher)
    private var tts: JarvisTts? = null
    private var hybrid: HybridTtsBridge? = null
    private var speakJob: Job? = null
    @Volatile private var speakGeneration: Int = 0
    @Volatile var lastTtsTelemetry: TtsTelemetry? = null
        private set

    val ttsAvailable: Boolean get() = tts != null || hybrid != null
    /** Любой вывод (речь или чим) — для UI. */
    val playing: Boolean get() = player.isPlaying
    /** Только TTS-речь: VoiceEngine глушит mic только под это (чим не режет команду после wake). */
    val speaking: Boolean get() = speechPlaying
    val lastPlayEndAtMs: Long get() = _lastPlayEndAtMs

    @Volatile private var _lastPlayEndAtMs = 0L
    @Volatile private var speechPlaying = false

    private fun setPlayEnd() {
        _lastPlayEndAtMs = SystemClock.elapsedRealtime()
    }

    private fun resolveTtsDir(context: Context): File {
        val root = File(context.getExternalFilesDir(null), "models")
        val medium = File(root, TTS_MODEL_DIR)
        val int8 = File(root, TTS_MODEL_DIR_INT8)
        return when {
            File(medium, "ru_RU-dmitri-medium.onnx").isFile -> medium
            File(int8, "ru_RU-dmitri-medium.onnx").isFile -> int8
            medium.isDirectory -> medium
            else -> int8
        }
    }

    init {
        if (ttsDir.isDirectory) {
            runCatching {
                tts = JarvisTts(
                    JarvisTtsConfig(
                        modelDir = ttsDir,
                        numThreads = 4,
                        speed = 1.08f,
                    ),
                )
                Log.d(TAG, "TTS ready (${tts!!.sampleRate} Hz) dir=${ttsDir.name}")
                hybrid = HybridTtsBridge(appCtx) { tts }
                Log.i(TAG, "hybrid TTS ${hybrid!!.decisionLabel()}")
                scope.launch {
                    runCatching {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                        tts?.synthesize("ок")
                        Log.d(TAG, "TTS warm-up done")
                    }.onFailure { Log.w(TAG, "TTS warm-up failed: ${it.message}") }
                }
            }.onFailure {
                Log.e(TAG, "TTS init failed: ${it.message}")
                tts = null
                hybrid = HybridTtsBridge(appCtx) { null }
            }
        } else {
            Log.d(TAG, "TTS model dir missing: ${ttsDir.absolutePath}")
            hybrid = HybridTtsBridge(appCtx) { null }
        }
    }

    fun onWakeDetected() {
        stopSpeaking()
        // Чим асинхронно и без mic-gate / setPlayEnd — иначе ~0.4–0.6 с глухоты
        // поверх команды «джарвис, …» (частая жалоба тестеров).
        scope.launch {
            runCatching { player.playChime() }
        }
    }

    fun sayListening() = speak("Слушаю.")

    fun speakResponse(
        text: String,
        onComplete: () -> Unit = {},
        onFirstAudio: ((Long) -> Unit)? = null,
    ) = speak(text, onComplete, onFirstAudio)

    fun stop() = stopSpeaking()

    /**
     * V2-07: queue sentence chunks for sequential TTS without canceling prior chunk mid-play.
     * [onFirstAudio] fires once for the first chunk of this stream generation.
     */
    fun enqueueSpeak(
        text: String,
        streamGeneration: Int,
        onFirstAudio: ((Long) -> Unit)? = null,
        onChunkDone: () -> Unit = {},
    ) {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) {
            onChunkDone()
            return
        }
        if (hybrid == null && tts == null) {
            onFirstAudio?.invoke(0L)
            onChunkDone()
            return
        }
        synchronized(utteranceQueue) {
            // Backpressure: drop oldest pending (not currently playing) when over capacity
            while (utteranceQueue.size >= MAX_QUEUE_DEPTH) {
                val dropped = utteranceQueue.removeFirst()
                dropped.onDone()
            }
            utteranceQueue.addLast(
                QueuedUtterance(cleaned, streamGeneration, onFirstAudio, onChunkDone),
            )
            if (!queuePumpRunning) {
                queuePumpRunning = true
                pumpUtteranceQueue()
            }
        }
    }

    fun beginStreamSpeak(): Int {
        stopSpeaking()
        // stopSpeaking already bumped speakGeneration
        streamSpeakGeneration = speakGeneration
        streamFirstAudioSent = false
        return speakGeneration
    }

    fun endStreamSpeak(onComplete: () -> Unit = {}) {
        synchronized(utteranceQueue) {
            utteranceQueue.addLast(
                QueuedUtterance("", streamSpeakGeneration, null, onComplete, isEndMarker = true),
            )
            if (!queuePumpRunning) {
                queuePumpRunning = true
                pumpUtteranceQueue()
            }
        }
    }

    private data class QueuedUtterance(
        val text: String,
        val streamGen: Int,
        val onFirstAudio: ((Long) -> Unit)?,
        val onDone: () -> Unit,
        val isEndMarker: Boolean = false,
    )

    private val utteranceQueue = ArrayDeque<QueuedUtterance>()
    @Volatile private var queuePumpRunning = false
    @Volatile private var streamSpeakGeneration = 0
    @Volatile private var streamFirstAudioSent = false

    private fun synthPcm(text: String, gen: Int, queueDepth: Int): Pair<FloatArray, Int> {
        val bridge = hybrid
        if (bridge != null) {
            val result = bridge.synthesize(
                text = text,
                cancelFlag = { gen != speakGeneration },
                queueDepth = queueDepth,
            )
            lastTtsTelemetry = bridge.lastTelemetry
            return result.samples to result.sampleRateHz
        }
        val engine = tts ?: return FloatArray(0) to 22_050
        if (gen != speakGeneration) return FloatArray(0) to engine.sampleRate
        return engine.synthesize(text) to engine.sampleRate
    }

    private fun pumpUtteranceQueue() {
        speakJob = scope.launch {
            speechPlaying = true
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                while (true) {
                    val (next, depth) = synchronized(utteranceQueue) {
                        if (utteranceQueue.isEmpty()) {
                            queuePumpRunning = false
                            null to 0
                        } else {
                            utteranceQueue.removeFirst() to utteranceQueue.size
                        }
                    }
                    if (next == null) break
                    if (next.streamGen != speakGeneration) {
                        next.onDone()
                        continue
                    }
                    if (next.isEndMarker) {
                        next.onDone()
                        continue
                    }
                    val t0 = SystemClock.elapsedRealtime()
                    val (samples, rate) = synthPcm(next.text, next.streamGen, depth)
                    if (next.streamGen != speakGeneration) {
                        next.onDone()
                        continue
                    }
                    if (samples.isNotEmpty()) {
                        if (!streamFirstAudioSent) {
                            streamFirstAudioSent = true
                            val audioMs = SystemClock.elapsedRealtime() - t0
                            lastTtsTelemetry?.ttsFirstAudioMs = audioMs
                            lastTtsTelemetry?.ttsFirstPlaybackMs = audioMs
                            next.onFirstAudio?.invoke(audioMs)
                        }
                        val playStart = SystemClock.elapsedRealtime()
                        player.play(samples, rate)
                        lastTtsTelemetry?.ttsPlaybackMs =
                            (lastTtsTelemetry?.ttsPlaybackMs ?: 0L) +
                                (SystemClock.elapsedRealtime() - playStart)
                    } else if (!streamFirstAudioSent) {
                        streamFirstAudioSent = true
                        next.onFirstAudio?.invoke(0L)
                    }
                    next.onDone()
                }
            } finally {
                speechPlaying = false
                setPlayEnd()
                lastTtsTelemetry?.ttsTotalMs =
                    lastTtsTelemetry?.ttsFirstAudioMs
                synchronized(utteranceQueue) {
                    if (utteranceQueue.isEmpty()) queuePumpRunning = false
                    else {
                        queuePumpRunning = true
                        pumpUtteranceQueue()
                    }
                }
            }
        }
    }

    private fun stopSpeaking() {
        speakGeneration++
        streamFirstAudioSent = false
        lastTtsTelemetry?.ttsCancelled = true
        synchronized(utteranceQueue) {
            utteranceQueue.clear()
            queuePumpRunning = false
        }
        speakJob?.cancel()
        speechPlaying = false
        player.stop()
    }

    private fun speak(
        text: String,
        onComplete: () -> Unit = {},
        onFirstAudio: ((Long) -> Unit)? = null,
    ) {
        if (hybrid == null && tts == null) {
            Log.w(TAG, "speak: TTS engine null")
            onFirstAudio?.invoke(0L)
            onComplete()
            return
        }
        speakJob?.cancel()
        player.stop()
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) {
            onFirstAudio?.invoke(0L)
            onComplete()
            return
        }
        val gen = ++speakGeneration
        val t0 = SystemClock.elapsedRealtime()
        speakJob = scope.launch {
            speechPlaying = true
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                var firstAudioLogged = false
                fun noteFirst() {
                    if (firstAudioLogged || gen != speakGeneration) return
                    firstAudioLogged = true
                    val ms = SystemClock.elapsedRealtime() - t0
                    Log.d(TAG, "tts first-audio ${ms}ms")
                    lastTtsTelemetry?.ttsFirstAudioMs = ms
                    lastTtsTelemetry?.ttsFirstPlaybackMs = ms
                    onFirstAudio?.invoke(ms)
                }

                if (cleaned.length <= FULL_SYNTH_MAX_CHARS) {
                    Log.d(TAG, "speak full chars=${cleaned.length}")
                    val (samples, rate) = synthPcm(cleaned, gen, 0)
                    if (gen != speakGeneration) return@launch
                    if (samples.isNotEmpty()) {
                        noteFirst()
                        player.play(samples, rate)
                    } else {
                        noteFirst()
                    }
                } else {
                    val sentences = splitSentences(cleaned)
                    Log.d(TAG, "speak continuous sentences=${sentences.size} chars=${cleaned.length}")
                    var rate = 22_050
                    val blocks = sequence {
                        for (s in sentences) {
                            if (gen != speakGeneration) break
                            val (pcm, r) = synthPcm(s, gen, 0)
                            rate = r
                            if (pcm.isNotEmpty()) yield(pcm)
                        }
                    }
                    player.playContinuous(
                        sampleRate = rate,
                        onFirstWrite = { noteFirst() },
                        blocks = blocks,
                    )
                    if (!firstAudioLogged) noteFirst()
                }

                if (gen == speakGeneration) {
                    setPlayEnd()
                    Log.d(TAG, "tts done ${SystemClock.elapsedRealtime() - t0}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "speak failed: ${e.message}")
                onFirstAudio?.invoke(0L)
            } finally {
                speechPlaying = false
                if (gen == speakGeneration) onComplete()
            }
        }
    }

    /** Только по концу предложения — естественные паузы, не рубка mid-phrase. */
    internal fun splitSentences(text: String): List<String> {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return listOf("")
        val parts = cleaned.split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(cleaned) }
    }

    fun release() {
        speakGeneration++
        scope.cancel()
        ttsExecutor.shutdownNow()
        player.release()
        tts?.release()
        tts = null
    }
}
