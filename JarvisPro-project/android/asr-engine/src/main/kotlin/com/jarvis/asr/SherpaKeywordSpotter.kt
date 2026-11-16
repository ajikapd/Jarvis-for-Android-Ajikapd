package com.jarvis.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Второй детектор «Джарвис»: sherpa KWS на русской ASR-модели, без обучения на слово.
 * Сильнее openWakeWord на тихой и дальней речи, поэтому работает параллельно с ним.
 * Усиление [GAIN] подобрано по записи голоса: без него тихие слова не слышны.
 */
class SherpaKeywordSpotter(
    modelDir: String,
    keywordsFile: File,
    spec: AsrModelSpec = AsrModelSpec.RU_STREAMING,
) : AutoCloseable {

    private val spotter: KeywordSpotter
    private val stream: OnlineStream
    private var busyNs = 0L
    private var fedSamples = 0L

    init {
        keywordsFile.writeText(KEYWORDS)
        spotter = KeywordSpotter(
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/${spec.encoderFile}",
                        decoder = "$modelDir/${spec.decoderFile}",
                        joiner = "$modelDir/${spec.joinerFile}",
                    ),
                    tokens = "$modelDir/${spec.tokensFile}",
                    modelType = spec.modelType,
                    numThreads = 1,
                ),
                maxActivePaths = MAX_ACTIVE_PATHS,
                keywordsFile = keywordsFile.absolutePath,
                keywordsScore = KEYWORDS_SCORE,
                keywordsThreshold = KEYWORDS_THRESHOLD,
                numTrailingBlanks = NUM_TRAILING_BLANKS,
            ),
        )
        stream = spotter.createStream("")
    }

    /** @return true, если в этом чанке распознано слово. */
    fun process(samples: FloatArray): Boolean {
        val started = System.nanoTime()
        stream.acceptWaveform(amplified(samples), SAMPLE_RATE)
        var hit = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                hit = true
                spotter.reset(stream)
            }
        }
        logLoad(samples.size, System.nanoTime() - started)
        return hit
    }

    fun reset() = spotter.reset(stream)

    override fun close() {
        stream.release()
        spotter.release()
    }

    /** Доля реального времени, которую занимает детектор (для замера нагрузки). */
    private fun logLoad(samples: Int, ns: Long) {
        busyNs += ns
        fedSamples += samples
        if (fedSamples < SAMPLE_RATE * LOAD_LOG_SECONDS) return
        val rtf = busyNs / 1e9 / (fedSamples.toDouble() / SAMPLE_RATE)
        Log.i(TAG, "load rtf=${"%.3f".format(rtf)} seconds=${fedSamples / SAMPLE_RATE}")
        busyNs = 0
        fedSamples = 0
    }

    companion object {
        private const val TAG = "Jarvis.KWS2"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val GAIN = 10f
        private const val MAX_ACTIVE_PATHS = 4
        private const val KEYWORDS_SCORE = 2f
        private const val KEYWORDS_THRESHOLD = 0.1f
        private const val NUM_TRAILING_BLANKS = 1
        private const val LOAD_LOG_SECONDS = 60

        /** Токены модели по вариантам, которые ASR слышит в «Джарвис». */
        internal val KEYWORDS = listOf(
            "▁д жа р ви с @джарвис",
            "▁д жа р в е с @джарвес",
            "▁д ж е р ви с @джервис",
            "▁д жа р ви з @джарвиз",
            "▁ж а р ви с @жарвис",
        ).joinToString("\n", postfix = "\n")

        internal fun amplified(samples: FloatArray): FloatArray =
            FloatArray(samples.size) { (samples[it] * GAIN).coerceIn(-1f, 1f) }

        fun tryCreate(modelDir: String, keywordsFile: File): SherpaKeywordSpotter? = try {
            SherpaKeywordSpotter(modelDir, keywordsFile)
        } catch (t: Throwable) {
            Log.e(TAG, "init failed: ${t.message}", t)
            null
        }
    }
}
