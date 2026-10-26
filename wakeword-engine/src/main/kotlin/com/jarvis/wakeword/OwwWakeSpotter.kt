package com.jarvis.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Offline KWS «Джарвис» (openWakeWord):
 * melspectrogram → embedding → djarvis.onnx.
 *
 * Пороги подобраны под реальные mic-скоры модели (часто 0.15–0.35 на голосе),
 * а не под synthetic/loopback ~0.9.
 */
class OwwWakeSpotter(
    context: Context,
    @Volatile private var threshold: Float = DEFAULT_THRESHOLD,
    @Volatile private var patience: Int = DEFAULT_PATIENCE,
) : AutoCloseable {

    private val env: OrtEnvironment
    private val melSession: OrtSession
    private val embSession: OrtSession
    private val clfSession: OrtSession
    private val clfInput: String
    private val features: OwwAudioFeatures

    private var framesSinceStart = 0
    private var aboveCount = 0
    private var lastHitAtMs = 0L
    private var lastScoreLogAtMs = 0L
    private var peakScore = 0f
    private var lastScore = 0f
    /** Плавный AGC: тихий/дальний «джарвис» поднимаем до целевого RMS. */
    private var agcGain = 1f
    /** EMA шума для адаптивного порога (тише комната → легче сработку). */
    private var noiseEma = 0.008f

    /** Кольцо последних скоров (~0.8 с) — ловим короткий/тихий пик. */
    private val scoreRing = FloatArray(SCORE_RING) { 0f }
    private var scoreRingPos = 0
    private var scoreRingFilled = 0

    @Volatile private var armed = true
    @Volatile private var armAfterMs = 0L

    val engine: String = "openwakeword-djarvis"
    val lastScoreValue: Float get() = lastScore
    val isArmed: Boolean get() = armed

    init {
        runCatching { System.loadLibrary("onnxruntime_oww") }
        runCatching { System.loadLibrary("onnxruntime4j_jni") }
        env = OrtEnvironment.getEnvironment()

        val assets = context.applicationContext.assets
        fun load(name: String): OrtSession =
            assets.open("$ASSET_DIR/$name").use { env.createSession(it.readBytes()) }

        melSession = load(MEL_MODEL)
        embSession = load(EMB_MODEL)
        clfSession = load(CLF_MODEL)
        clfInput = clfSession.inputNames.first()
        features = OwwAudioFeatures(env, melSession, embSession)

        Log.i(
            TAG,
            "ready thr=$threshold patience=$patience " +
                "in=${clfSession.inputInfo.keys} feats=${features.featureCount}",
        )
    }

    /** Обновить порог без пересоздания ORT (смена чувствительности в UI). */
    fun applySensitivity(level: Int) {
        threshold = thresholdFromLevel(level)
        patience = patienceFromLevel(level)
        aboveCount = 0
        clearScoreRing()
        Log.i(TAG, "sensitivity=$level thr=$threshold patience=$patience")
    }

    /**
     * @param samples PCM ≈[-1,1] из AudioCapture
     * @return true при wake; после HIT — disarm до [armForNextWake]
     */
    fun process(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false

        val boosted = applyAgc(samples)
        // OWW ждёт float в шкале int16; без toInt — меньше потери на тихой речи.
        val pcm = FloatArray(boosted.size) { i ->
            (boosted[i] * 32768f).coerceIn(-32768f, 32767f)
        }

        // Даже в cooldown кормим фичи — иначе после паузы «слепой» буфер.
        val prepared = features.accept(pcm)
        if (prepared < OwwAudioFeatures.CHUNK ||
            features.featureCount < OwwAudioFeatures.FEATURE_FRAMES
        ) {
            return false
        }

        framesSinceStart++
        val now = System.currentTimeMillis()

        if (!armed) return false
        if (now < armAfterMs) return false
        if (framesSinceStart <= WARMUP_FRAMES) return false

        val score = predictScore(prepared)
        lastScore = score
        peakScore = max(peakScore, score)
        pushScore(score)
        val windowMax = windowMax()

        // Адаптивный пол: в тишине порог ещё ниже фиксированного high.
        if (score < threshold * 0.45f) {
            noiseEma = noiseEma * 0.97f + score * 0.03f
        }
        val effectiveThr = max(MIN_TRIGGER, min(threshold, noiseEma * 4.5f + 0.012f))

        if (now - lastScoreLogAtMs >= SCORE_LOG_MS) {
            lastScoreLogAtMs = now
            Log.i(
                TAG,
                "score=${"%.3f".format(score)} win=${"%.3f".format(windowMax)} " +
                    "peak=${"%.3f".format(peakScore)} thr=${"%.3f".format(effectiveThr)} " +
                    "base=$threshold agc=${"%.1f".format(agcGain)}x armed=$armed",
            )
            peakScore = 0f
        }

        val trigger = windowMax >= effectiveThr
        if (trigger) {
            aboveCount++
        } else {
            aboveCount = 0
        }

        if (aboveCount < patience) return false
        if (now - lastHitAtMs < DEDUP_MS) return false

        lastHitAtMs = now
        aboveCount = 0
        armed = false
        clearScoreRing()
        Log.i(
            TAG,
            "HIT «джарвис» score=${"%.3f".format(score)} win=${"%.3f".format(windowMax)} " +
                "thr=${"%.3f".format(effectiveThr)} agc=${"%.1f".format(agcGain)}x → disarm",
        )
        return true
    }

    /**
     * Подъём только тихих/дальних чанков. Громкую речь в упор не трогаем —
     * иначе AGC на шуме уходит в 10×+ и клиппит «джарвис» → 2–3 повтора.
     */
    private fun applyAgc(samples: FloatArray): FloatArray {
        var sumSq = 0.0
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
            sumSq += (s * s).toDouble()
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size).toFloat()

        // Громко / в упор — быстро сбрасываем усиление, почти raw.
        if (peak >= 0.22f || rms >= 0.06f) {
            val desired = when {
                peak >= 0.65f -> 1f
                peak >= 0.40f -> (0.45f / peak).coerceIn(1f, 2.2f)
                else -> (AGC_TARGET_RMS / rms.coerceAtLeast(1e-4f)).coerceIn(1f, 3f)
            }
            agcGain += (desired - agcGain) * 0.55f
            if (agcGain <= 1.12f) {
                agcGain = 1f
                return samples
            }
            val g = agcGain
            return FloatArray(samples.size) { i ->
                (samples[i] * g).coerceIn(-0.98f, 0.98f)
            }
        }

        // Тишина — не накачиваем gain (иначе следующий громкий кадр в клип).
        if (rms < AGC_SILENCE_RMS || peak < AGC_SILENCE_RMS * 2f) {
            agcGain += (1f - agcGain) * 0.12f
            return samples
        }

        // Тихая/дальняя речь — поднимаем к целевому пику.
        val desired = (AGC_TARGET_PEAK / peak.coerceAtLeast(1e-4f)).coerceIn(1f, AGC_MAX_GAIN)
        val alpha = if (desired > agcGain) 0.28f else 0.20f
        agcGain += (desired - agcGain) * alpha
        if (agcGain <= 1.12f) return samples
        val g = agcGain
        return FloatArray(samples.size) { i ->
            (samples[i] * g).coerceIn(-0.98f, 0.98f)
        }
    }

    fun disarm() {
        armed = false
        aboveCount = 0
        clearScoreRing()
    }

    /**
     * Снова ждать wake после сессии. Сброс фич — чтобы не поймать старый «джарвис».
     * Во время cooldown [process] продолжает копить новый звук.
     */
    fun armForNextWake(cooldownMs: Long = POST_SESSION_COOLDOWN_MS) {
        features.reset()
        framesSinceStart = 0
        aboveCount = 0
        lastScore = 0f
        peakScore = 0f
        agcGain = 1f
        noiseEma = 0.008f
        clearScoreRing()
        armAfterMs = System.currentTimeMillis() + cooldownMs.coerceAtLeast(0L)
        armed = true
        Log.i(TAG, "armed for next wake cooldown=${cooldownMs}ms thr=$threshold")
    }

    fun reset() {
        features.reset()
        framesSinceStart = 0
        aboveCount = 0
        lastScore = 0f
        peakScore = 0f
        agcGain = 1f
        noiseEma = 0.008f
        clearScoreRing()
    }

    private fun pushScore(score: Float) {
        scoreRing[scoreRingPos] = score
        scoreRingPos = (scoreRingPos + 1) % SCORE_RING
        if (scoreRingFilled < SCORE_RING) scoreRingFilled++
    }

    private fun windowMax(): Float {
        var m = 0f
        val n = scoreRingFilled
        for (i in 0 until n) m = max(m, scoreRing[i])
        return m
    }

    private fun clearScoreRing() {
        scoreRing.fill(0f)
        scoreRingPos = 0
        scoreRingFilled = 0
    }

    private fun predictScore(prepared: Int): Float {
        val n = OwwAudioFeatures.FEATURE_FRAMES
        return try {
            if (prepared > OwwAudioFeatures.CHUNK) {
                val steps = prepared / OwwAudioFeatures.CHUNK
                var best = 0f
                for (i in (steps - 1) downTo 0) {
                    val feats = features.getFeaturesAt(n, startFromEnd = i)
                    best = max(best, runClassifier(feats))
                }
                best
            } else {
                runClassifier(features.getFeatures(n))
            }
        } catch (e: Exception) {
            Log.w(TAG, "predict: ${e.message}")
            0f
        }
    }

    private fun runClassifier(feats: Array<Array<FloatArray>>): Float {
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(env, feats)
            clfSession.run(mapOf(clfInput to tensor)).use { outs ->
                readScore(outs[0].value)
            }
        } finally {
            tensor?.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readScore(value: Any): Float {
        return try {
            (value as Array<FloatArray>)[0][0]
        } catch (_: ClassCastException) {
            try {
                (value as Array<Array<FloatArray>>)[0][0][0]
            } catch (_: ClassCastException) {
                when (value) {
                    is FloatArray -> value[0]
                    is Float -> value
                    else -> {
                        Log.w(TAG, "clf shape ${value::class.java.name}")
                        0f
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { features.close() }
        runCatching { clfSession.close() }
        runCatching { embSession.close() }
        runCatching { melSession.close() }
    }

    companion object {
        private const val TAG = "Jarvis.KWS"
        private const val ASSET_DIR = "openwakeword"
        private const val MEL_MODEL = "melspectrogram.onnx"
        private const val EMB_MODEL = "embedding_model.onnx"
        private const val CLF_MODEL = "djarvis.onnx"

        /** Максимально мягкий high — пользователь всё ещё ловит промахи в упор. */
        const val DEFAULT_THRESHOLD = 0.06f
        private const val DEFAULT_PATIENCE = 1
        private const val WARMUP_FRAMES = 2
        private const val DEDUP_MS = 900L
        private const val SCORE_LOG_MS = 600L
        private const val SCORE_RING = 14
        const val POST_SESSION_COOLDOWN_MS = 700L
        private const val MIN_TRIGGER = 0.015f
        private const val AGC_TARGET_RMS = 0.09f
        private const val AGC_TARGET_PEAK = 0.35f
        private const val AGC_SILENCE_RMS = 0.003f
        private const val AGC_MAX_GAIN = 8f

        fun thresholdFromLevel(level: Int): Float = when (level) {
            0 -> 0.12f
            2 -> 0.02f
            else -> DEFAULT_THRESHOLD
        }

        fun patienceFromLevel(level: Int): Int = 1

        fun tryCreate(context: Context, sensitivityLevel: Int): OwwWakeSpotter? {
            return try {
                OwwWakeSpotter(
                    context = context,
                    threshold = thresholdFromLevel(sensitivityLevel),
                    patience = patienceFromLevel(sensitivityLevel),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "init failed: ${t.message}", t)
                null
            }
        }
    }
}
