package com.jarvis.app

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.jarvis.app.tts.HybridTtsBridge
import com.jarvis.core.voice.TtsTelemetry
import com.jarvis.core.nlp.JarvisStyle
import com.jarvis.core.nlp.SpeechTextNormalizer
import com.jarvis.tts.JarvisTts
import com.jarvis.tts.JarvisVoiceFx
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
        /** Дообученный голос Джарвиса: своя папка, потому что его словарь eSpeak исправлен и Dmitri не подходит. */
        private const val JARVIS_VOICE_DIR = "jarvis-voice-ru"
        private const val JARVIS_VOICE_ONNX = "jarvis.onnx"
        private const val JARVIS_VOICE_SPEED = 1.12f
        private const val DMITRI_ONNX = "ru_RU-dmitri-medium.onnx"
        private const val DMITRI_SPEED = 1.08f
        private const val TAG = "Jarvis.Speaker"
        /** Большинство ответов целиком — один гладкий play. */
        private const val FULL_SYNTH_MAX_CHARS = 160
        /** Bound stream queue depth (sentence chunks). */
        private const val MAX_QUEUE_DEPTH = 12
        /** Сколько готовых фраз может ждать воспроизведения. */
        private const val READ_AHEAD_CHUNKS = 2

        /** Голоса по порядку предпочтения: первым идёт свой, Dmitri остаётся запасным. */
        fun voiceConfigs(context: Context): List<JarvisTtsConfig> {
            val root = File(context.getExternalFilesDir(null), "models")
            val own = File(root, JARVIS_VOICE_DIR)
            return buildList {
                if (isVoiceComplete(own, JARVIS_VOICE_ONNX)) {
                    add(JarvisTtsConfig(own, modelOnnx = JARVIS_VOICE_ONNX, numThreads = 4, speed = JARVIS_VOICE_SPEED))
                }
                listOf(TTS_MODEL_DIR, TTS_MODEL_DIR_INT8)
                    .map { File(root, it) }
                    .firstOrNull { File(it, DMITRI_ONNX).isFile }
                    ?.let { add(JarvisTtsConfig(it, numThreads = 4, speed = DMITRI_SPEED)) }
            }
        }

        /** Сбой sherpa при неполной папке роняет процесс, поэтому полноту проверяем заранее. */
        private fun isVoiceComplete(dir: File, onnx: String): Boolean =
            File(dir, onnx).isFile && File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data/ru_dict").isFile
    }

    private val appCtx = context.applicationContext
    private val foreignVoice by lazy { com.jarvis.app.tts.AndroidSystemTtsBackend(appCtx) }
    private val player = TtsPlayer()
    /** Свой плеер: иначе остановка речи при активации обрывает и сам сигнал. */
    private val chimePlayer = TtsPlayer()
    private val ttsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-tts").apply { priority = Thread.MAX_PRIORITY }
    }
    private val ttsDispatcher = ttsExecutor.asCoroutineDispatcher()
    private val chimeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-chime") }
    /** Звук играет отдельно от синтеза: следующая фраза готовится, пока звучит текущая. */
    private val playExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-play").apply { priority = Thread.MAX_PRIORITY }
    }
    private val readAhead = java.util.concurrent.Semaphore(READ_AHEAD_CHUNKS)
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
    /** Джарвис сам произносит своё имя: перебивание должно быть строже, иначе услышит себя. */
    @Volatile var saysOwnName = false
        private set
    private var listeningTurn = 0

    private fun setPlayEnd() {
        _lastPlayEndAtMs = SystemClock.elapsedRealtime()
    }

    init {
        val config = voiceConfigs(context).firstNotNullOfOrNull { candidate ->
            runCatching { tts = JarvisTts(candidate) }
                .onFailure { Log.e(TAG, "TTS init failed: ${candidate.modelDir.name}: ${it.message}") }
                .map { candidate }
                .getOrNull()
        }
        hybrid = HybridTtsBridge(appCtx, config?.modelDir?.name ?: TTS_MODEL_DIR) { tts }
        if (config != null) {
            Log.i(TAG, "TTS ready (${tts!!.sampleRate} Hz) dir=${config.modelDir.name} speed=${config.speed}")
            Log.i(TAG, "hybrid TTS ${hybrid!!.decisionLabel()}")
            scope.launch {
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    tts?.synthesize("ок")
                    Log.d(TAG, "TTS warm-up done")
                }.onFailure { Log.w(TAG, "TTS warm-up failed: ${it.message}") }
            }
        } else {
            Log.w(TAG, "TTS: нет ни одного локального голоса")
        }
    }

    /** @param chime false при перебивании: прервавшаяся речь сама показывает, что он услышал. */
    fun onWakeDetected(chime: Boolean = true) {
        stopSpeaking()
        if (!chime) return
        // Чим асинхронно и без mic-gate / setPlayEnd — иначе ~0.4–0.6 с глухоты
        // поверх команды «джарвис, …» (частая жалоба тестеров).
        chimeExecutor.execute { runCatching { chimePlayer.playChime() } }
    }

    fun sayListening() = speak(JarvisStyle.listening(listeningTurn++))

    fun speakResponse(
        text: String,
        onComplete: () -> Unit = {},
        onFirstAudio: ((Long) -> Unit)? = null,
    ) = speak(text, onComplete, onFirstAudio)

    fun stop() = stopSpeaking()

    /** true, пока проигрывается хоть один синтезированный фрагмент. */
    fun isSpeaking(): Boolean = speechPlaying

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
        val cleaned = address.apply(text.trim().replace(Regex("\\s+"), " "))
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
        address.nextReply()
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
    private val address = com.jarvis.core.nlp.AddressRhythm()
    @Volatile private var queuePumpRunning = false
    @Volatile private var streamSpeakGeneration = 0
    @Volatile private var streamFirstAudioSent = false

    /** Иностранный текст читает системный голос: русская модель его не осилит. */
    private fun foreignPcm(raw: String, locale: String, gen: Int): Pair<FloatArray, Int>? {
        val result = foreignVoice.synthesize(
            raw, com.jarvis.core.voice.TtsVoiceConfig(language = locale), cancelFlag = { gen != speakGeneration },
        )
        return result.samples.takeIf { it.isNotEmpty() }?.let { JarvisVoiceFx.process(it, result.sampleRateHz) to result.sampleRateHz }
    }

    private fun synthPcm(raw: String, gen: Int, queueDepth: Int): Pair<FloatArray, Int> {
        com.jarvis.core.nlp.Translator.speechLocale(raw)?.let { locale ->
            foreignPcm(raw, locale, gen)?.let { return it }
        }
        saysOwnName = raw.contains("джарвис", ignoreCase = true)
        val text = SpeechTextNormalizer.forSpeech(raw)
        if (text.isBlank()) return FloatArray(0) to 22_050
        val bridge = hybrid
        if (bridge != null) {
            val result = bridge.synthesize(
                text = text,
                cancelFlag = { gen != speakGeneration },
                queueDepth = queueDepth,
            )
            lastTtsTelemetry = bridge.lastTelemetry
            return JarvisVoiceFx.process(result.samples, result.sampleRateHz) to result.sampleRateHz
        }
        val engine = tts ?: return FloatArray(0) to 22_050
        if (gen != speakGeneration) return FloatArray(0) to engine.sampleRate
        return JarvisVoiceFx.process(engine.synthesize(text), engine.sampleRate) to engine.sampleRate
    }

    private fun playQueued(next: QueuedUtterance, samples: FloatArray, rate: Int, t0: Long, synthMs: Long) {
        if (next.streamGen != speakGeneration) {
            next.onDone()
            return
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
            val waited = playStart - t0 - synthMs
            player.play(samples, rate)
            val playMs = SystemClock.elapsedRealtime() - playStart
            lastTtsTelemetry?.ttsPlaybackMs = (lastTtsTelemetry?.ttsPlaybackMs ?: 0L) + playMs
            Log.d(TAG, "chunk chars=${next.text.length} audio=${samples.size * 1000L / rate}ms synth=${synthMs}ms wait=${waited}ms play=${playMs}ms")
        } else if (!streamFirstAudioSent) {
            streamFirstAudioSent = true
            next.onFirstAudio?.invoke(0L)
        }
        next.onDone()
    }

    private fun pumpUtteranceQueue() {
        speakJob = scope.launch {
            speechPlaying = true
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                var lastPlay: java.util.concurrent.Future<*>? = null
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
                        lastPlay = playExecutor.submit { next.onDone() }
                        continue
                    }
                    val t0 = SystemClock.elapsedRealtime()
                    val (samples, rate) = synthPcm(next.text, next.streamGen, depth)
                    val synthMs = SystemClock.elapsedRealtime() - t0
                    if (next.streamGen != speakGeneration) {
                        next.onDone()
                        continue
                    }
                    readAhead.acquire()
                    lastPlay = playExecutor.submit {
                        try {
                            playQueued(next, samples, rate, t0, synthMs)
                        } finally {
                            readAhead.release()
                        }
                    }
                }
                lastPlay?.get()
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
        address.nextReply()
        val cleaned = address.apply(text.trim().replace(Regex("\\s+"), " "))
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
