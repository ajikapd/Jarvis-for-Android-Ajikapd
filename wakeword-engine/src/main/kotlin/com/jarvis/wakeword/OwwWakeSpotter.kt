package com.jarvis.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlin.math.max

/**
 * Offline KWS «Джарвис» (openWakeWord):
 * melspectrogram → embedding → djarvis.onnx.
 *
 * Сигнал идёт в модель как есть: усиление по чанкам портит слово (замер на записи голоса).
 * Скор на словах доходит до 0.99, на тишине и чужой речи не выше 0.003.
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
        // По умолчанию ORT держит пул на все ядра с активным ожиданием: 440% CPU в простое.
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        fun load(name: String): OrtSession =
            assets.open("$ASSET_DIR/$name").use { env.createSession(it.readBytes(), options) }

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
        Log.i(TAG, "sensitivity=$level thr=$threshold patience=$patience")
    }

    /**
     * @param samples PCM ≈[-1,1] из AudioCapture
     * @return true при wake; после HIT — disarm до [armForNextWake]
     */
    fun process(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false

        // OWW ждёт float в шкале int16; без toInt — меньше потери на тихой речи.
        val pcm = FloatArray(samples.size) { i -> samples[i] * 32768f }

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

        if (now - lastScoreLogAtMs >= SCORE_LOG_MS) {
            lastScoreLogAtMs = now
            Log.i(
                TAG,
                "score=${"%.3f".format(score)} peak=${"%.3f".format(peakScore)} " +
                    "thr=$threshold armed=$armed",
            )
            peakScore = 0f
        }

        if (score >= threshold) {
            aboveCount++
        } else {
            aboveCount = 0
        }

        if (aboveCount < patience) return false
        if (now - lastHitAtMs < DEDUP_MS) return false

        lastHitAtMs = now
        aboveCount = 0
        armed = false
        Log.i(TAG, "HIT «джарвис» score=${"%.3f".format(score)} thr=$threshold → disarm")
        return true
    }

    fun disarm() {
        armed = false
        aboveCount = 0
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

        const val DEFAULT_THRESHOLD = 0.05f
        private const val DEFAULT_PATIENCE = 1
        private const val WARMUP_FRAMES = 2
        private const val DEDUP_MS = 900L
        private const val SCORE_LOG_MS = 600L
        const val POST_SESSION_COOLDOWN_MS = 700L

        fun thresholdFromLevel(level: Int): Float = when (level) {
            0 -> 0.1f
            2 -> 0.03f
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
