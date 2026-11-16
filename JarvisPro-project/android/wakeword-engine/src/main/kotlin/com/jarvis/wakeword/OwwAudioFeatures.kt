package com.jarvis.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Порт openWakeWord `AudioFeatures` (utils.py v0.6.0).
 *
 * Вход: PCM float в шкале int16 (−32768…32767), 16 kHz.
 * Чанки копятся кратно 1280 сэмплам (80 мс) → mel → speech embedding 96-d.
 *
 * https://github.com/dscripka/openWakeWord/blob/v0.6.0/openwakeword/utils.py
 */
/**
 * Конец окна mel для шага [stepsBack] от конца буфера. В Python срез `buf[-76+ndx : ndx]`
 * считает отрицательный индекс от конца; здесь смещение явное, иначе шаг теряется.
 */
internal fun melWindowEnd(melSize: Int, stepsBack: Int): Int = melSize - EMBED_STEP * stepsBack

private const val EMBED_STEP = 8

internal class OwwAudioFeatures(
    private val env: OrtEnvironment,
    private val melSession: OrtSession,
    private val embSession: OrtSession,
) : AutoCloseable {

    private val melInput: String = melSession.inputNames.first()
    private val embInput: String = embSession.inputNames.first()

    /** Последние сэмплы для mel: простой массив, боксинг Float грузил GC на каждом чанке. */
    private var rawBuffer = FloatArray(0)
    private var rawRemainder = FloatArray(0)
    private var accumulatedSamples = 0
    private var melBuffer: Array<FloatArray> = Array(MEL_WINDOW) { FloatArray(MEL_BINS) { 1f } }
    private var featureBuffer: Array<FloatArray>

    init {
        featureBuffer = embeddingsFromPcm(warmNoise())
        Log.i(TAG, "ready feats=${featureBuffer.size}")
    }

    fun reset() {
        rawBuffer = FloatArray(0)
        rawRemainder = FloatArray(0)
        accumulatedSamples = 0
        melBuffer = Array(MEL_WINDOW) { FloatArray(MEL_BINS) { 1f } }
        featureBuffer = embeddingsFromPcm(warmNoise())
    }

    /**
     * Как `AudioFeatures.__call__` / `_streaming_features`.
     * @return число обработанных сэмплов (кратно CHUNK), иначе текущий накопленный остаток.
     */
    fun accept(pcmInt16Scale: FloatArray): Int {
        var x = pcmInt16Scale
        var processed = 0

        if (rawRemainder.isNotEmpty()) {
            x = rawRemainder + x
            rawRemainder = FloatArray(0)
        }

        if (accumulatedSamples + x.size >= CHUNK) {
            val rem = (accumulatedSamples + x.size) % CHUNK
            if (rem != 0) {
                val even = x.copyOfRange(0, x.size - rem)
                bufferRaw(even)
                accumulatedSamples += even.size
                rawRemainder = x.copyOfRange(x.size - rem, x.size)
            } else {
                bufferRaw(x)
                accumulatedSamples += x.size
                rawRemainder = FloatArray(0)
            }
        } else {
            accumulatedSamples += x.size
            bufferRaw(x)
        }

        if (accumulatedSamples >= CHUNK && accumulatedSamples % CHUNK == 0) {
            streamingMel(accumulatedSamples)
            val steps = accumulatedSamples / CHUNK
            // OWW: for i in arange(steps-1, -1, -1): ndx=-8*i; buf[-76+ndx : ndx]
            for (i in (steps - 1) downTo 0) {
                val start = melWindowEnd(melBuffer.size, i) - MEL_WINDOW
                if (start < 0) continue
                val window = Array(MEL_WINDOW) { r -> melBuffer[start + r] }
                val emb = embedBatch(arrayOf(window))
                if (emb.isNotEmpty()) {
                    featureBuffer = featureBuffer + emb
                }
            }
            processed = accumulatedSamples
            accumulatedSamples = 0
        }

        if (featureBuffer.size > FEATURE_MAX) {
            featureBuffer = featureBuffer.takeLast(FEATURE_MAX).toTypedArray()
        }
        return if (processed != 0) processed else accumulatedSamples
    }

    /** `get_features(n)` → shape [1, n, 96]. */
    fun getFeatures(nFrames: Int = FEATURE_FRAMES): Array<Array<FloatArray>> {
        val n = nFrames.coerceAtMost(featureBuffer.size)
        val start = featureBuffer.size - n
        return arrayOf(Array(n) { featureBuffer[start + it] })
    }

    /** Как OWW при `prepared > 1280`: фичи со смещением `start_ndx`. */
    fun getFeaturesAt(nFrames: Int, startFromEnd: Int): Array<Array<FloatArray>> {
        // start_ndx = -nFrames - i  →  end = len + start_ndx + nFrames (или len)
        val endExclusive = (featureBuffer.size - startFromEnd).coerceIn(0, featureBuffer.size)
        val start = (endExclusive - nFrames).coerceAtLeast(0)
        val n = (endExclusive - start).coerceAtLeast(0)
        if (n < nFrames) return getFeatures(nFrames)
        return arrayOf(Array(nFrames) { featureBuffer[start + it] })
    }

    val featureCount: Int get() = featureBuffer.size

    private fun warmNoise(): FloatArray =
        FloatArray(SAMPLE_RATE * 4) { Random.nextInt(-1000, 1000).toFloat() }

    private fun bufferRaw(data: FloatArray) {
        val merged = rawBuffer + data
        rawBuffer = if (merged.size > RAW_MAX) merged.copyOfRange(merged.size - RAW_MAX, merged.size) else merged
    }

    private fun streamingMel(nSamples: Int) {
        if (rawBuffer.size < 400) return
        val need = nSamples + 160 * 3
        val slice = rawBuffer.copyOfRange((rawBuffer.size - need).coerceAtLeast(0), rawBuffer.size)
        val frames = computeMel(slice)
        if (frames.isEmpty()) return
        melBuffer = melBuffer + frames
        if (melBuffer.size > MEL_MAX) {
            melBuffer = melBuffer.takeLast(MEL_MAX).toTypedArray()
        }
    }

    private fun embeddingsFromPcm(pcm: FloatArray): Array<FloatArray> {
        val spec = computeMel(pcm)
        if (spec.isEmpty()) return emptyArray()
        val windows = ArrayList<Array<FloatArray>>()
        var i = 0
        while (i + MEL_WINDOW <= spec.size) {
            windows += spec.sliceArray(i until i + MEL_WINDOW)
            i += EMBED_STEP
        }
        if (windows.isEmpty()) return emptyArray()
        return embedBatch(windows.toTypedArray())
    }

    private fun computeMel(pcm: FloatArray): Array<FloatArray> {
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(pcm),
                longArrayOf(1, pcm.size.toLong()),
            )
            melSession.run(mapOf(melInput to tensor)).use { outs ->
                squeezeMel(outs[0].value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "mel: ${e.message}")
            emptyArray()
        } finally {
            tensor?.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun squeezeMel(value: Any): Array<FloatArray> {
        // OWW: squeeze + (x/10 + 2)
        return try {
            val a = value as Array<Array<Array<FloatArray>>>
            val fr = a[0][0]
            Array(fr.size) { i ->
                FloatArray(fr[i].size) { j -> fr[i][j] / 10f + 2f }
            }
        } catch (_: ClassCastException) {
            try {
                val fr = (value as Array<Array<FloatArray>>)[0]
                Array(fr.size) { i ->
                    FloatArray(fr[i].size) { j -> fr[i][j] / 10f + 2f }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mel shape ${value::class.java.name}: ${e.message}")
                emptyArray()
            }
        }
    }

    private fun embedBatch(windows: Array<Array<FloatArray>>): Array<FloatArray> {
        if (windows.isEmpty()) return emptyArray()
        var tensor: OnnxTensor? = null
        return try {
            // [N, 76, 32, 1]
            val shaped = Array(windows.size) { w ->
                Array(MEL_WINDOW) { r ->
                    Array(MEL_BINS) { c -> floatArrayOf(windows[w][r][c]) }
                }
            }
            tensor = OnnxTensor.createTensor(env, shaped)
            embSession.run(mapOf(embInput to tensor)).use { outs ->
                parseEmbeddings(outs[0].value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "emb: ${e.message}")
            emptyArray()
        } finally {
            tensor?.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEmbeddings(value: Any): Array<FloatArray> {
        return try {
            val raw = value as Array<Array<Array<FloatArray>>>
            Array(raw.size) { i -> raw[i][0][0].copyOf() }
        } catch (_: ClassCastException) {
            try {
                val raw = value as Array<FloatArray>
                Array(raw.size) { i -> raw[i].copyOf() }
            } catch (e: Exception) {
                Log.w(TAG, "emb shape ${value::class.java.name}: ${e.message}")
                emptyArray()
            }
        }
    }

    override fun close() {
        // Sessions owned by OwwWakeSpotter.
    }

    companion object {
        private const val TAG = "Jarvis.OWW.Features"
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1280
        const val MEL_WINDOW = 76
        const val MEL_BINS = 32
        const val EMB_DIM = 96
        const val FEATURE_FRAMES = 16
        private const val FEATURE_MAX = 120
        private const val RAW_MAX = SAMPLE_RATE
        private const val MEL_MAX = 10 * 97
    }
}
