package com.jarvis.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.math.sqrt

/**
 * Английский streaming-zipformer в режиме «offline буфер»:
 * PCM команды целиком → lead/trail silence → текст.
 * Не крутится на wake; только второй проход после endpoint.
 *
 * Важно: не зовём inputFinished и не включаем endpoint — оба обнуляли
 * гипотезу на коротких клипах (<~1.5с).
 */
class BufferedEnAsr(
    modelDir: String,
    private val spec: AsrModelSpec = AsrModelSpec.EN_STREAMING,
) : AutoCloseable {

    private val lock = Any()
    private val recognizer: OnlineRecognizer
    private var released = false

    init {
        require(File(modelDir).isDirectory) { "EN model dir missing: $modelDir" }
        for (name in spec.requiredFiles()) {
            require(File(modelDir, name).isFile) { "EN model file missing: $name" }
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDir/${spec.encoderFile}",
                    decoder = "$modelDir/${spec.decoderFile}",
                    joiner = "$modelDir/${spec.joinerFile}",
                ),
                tokens = "$modelDir/${spec.tokensFile}",
                modelType = spec.modelType,
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(false, 1.2f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        recognizer = OnlineRecognizer(config = config)
        Log.i(TAG, "buffered EN ASR ready (${spec.dirName})")
    }

    fun decode(samples: FloatArray): String =
        synchronized(lock) {
            if (released || samples.isEmpty()) return ""
            val stream = recognizer.createStream()
            try {
                val pcm = maybeBoost(samples)
                val step = SAMPLE_RATE / 10 // 100 мс

                // Lead silence — стабилизирует encoder на коротких названиях
                feed(stream, FloatArray(LEAD_PAD_SAMPLES))

                var off = 0
                while (off < pcm.size) {
                    val end = minOf(off + step, pcm.size)
                    feed(stream, pcm.copyOfRange(off, end))
                    off = end
                }

                // Trail: крутим тишину, пока не появится текст (ранний выход = скорость)
                var best = ""
                var trailed = 0
                while (trailed < TRAIL_PAD_SAMPLES) {
                    val n = minOf(step, TRAIL_PAD_SAMPLES - trailed)
                    feed(stream, FloatArray(n))
                    trailed += n
                    val t = recognizer.getResult(stream).text.trim()
                    if (t.isNotBlank()) {
                        best = t
                        // ещё ~200мс тишины для стабилизации, затем стоп
                        if (trailed >= EARLY_EXIT_AFTER_SAMPLES) break
                    }
                }

                if (best.isBlank()) {
                    Log.w(TAG, "EN decode empty (pcm=${samples.size})")
                } else {
                    Log.i(TAG, "EN decode=\"$best\" (pcm=${samples.size})")
                }
                best
            } finally {
                stream.release()
            }
        }

    private fun feed(stream: com.k2fsa.sherpa.onnx.OnlineStream, chunk: FloatArray) {
        if (chunk.isEmpty()) return
        stream.acceptWaveform(chunk, SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    /** Поднимаем тихий TTS/дальнюю речь — иначе 20M zipformer часто молчит. */
    private fun maybeBoost(samples: FloatArray): FloatArray {
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        val rms = sqrt(sum / samples.size).toFloat()
        if (rms < 0.01f || rms > 0.08f) return samples
        val gain = (0.12f / rms).coerceIn(1.5f, 8f)
        return FloatArray(samples.size) { i ->
            (samples[i] * gain).coerceIn(-0.99f, 0.99f)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (released) return
            released = true
            recognizer.release()
        }
    }

    companion object {
        private const val TAG = "Jarvis.Asr"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        private const val LEAD_PAD_SAMPLES = SAMPLE_RATE / 4 // 0.25 с
        private const val TRAIL_PAD_SAMPLES = SAMPLE_RATE // 1.0 с max
        private const val EARLY_EXIT_AFTER_SAMPLES = SAMPLE_RATE / 5 // +0.2 с после текста
    }
}

/** Результат второго прохода по PCM команды. */
data class CommandRefinePasses(
    val multilingual: String? = null,
    val english: String? = null,
)
