package com.jarvis.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Offline multilingual ASR (NeMo FastConformer CTC): RU+EN (+ ещё 8 языков).
 * Используется как второй проход по буферу команды после wake —
 * одна модель вместо сломанной склейки двух streaming-потоков.
 */
class OfflineMultilingualAsr(modelDir: String) : AutoCloseable {

    private val lock = Any()
    private val recognizer: OfflineRecognizer
    private var released = false

    init {
        require(File(modelDir).isDirectory) { "Model dir not found: $modelDir" }
        val modelFile = listOf("model.int8.onnx", "model.onnx")
            .map { File(modelDir, it) }
            .firstOrNull { it.isFile }
            ?: error("No model.int8.onnx / model.onnx in $modelDir")
        val tokens = File(modelDir, "tokens.txt")
        require(tokens.isFile) { "tokens.txt missing in $modelDir" }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath),
                tokens = tokens.absolutePath,
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        Log.i(TAG, "offline multilingual ready from ${modelFile.name}")
    }

    /** Распознать целый utterance (PCM float mono 16 kHz). */
    fun decode(samples: FloatArray): String =
        synchronized(lock) {
            if (released || samples.isEmpty()) return ""
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
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
        const val MODEL_DIR = "sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8"
    }
}
