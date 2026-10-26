package com.jarvis.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

data class JarvisTtsConfig(
    val modelDir: File,
    val modelOnnx: String = "ru_RU-dmitri-medium.onnx",
    val numThreads: Int = 4,
    val speed: Float = 1.12f,
)

class JarvisTts(config: JarvisTtsConfig) {

    private val tts: OfflineTts
    private val speed = config.speed
    val sampleRate: Int get() = tts.sampleRate()

    init {
        val vits = OfflineTtsVitsModelConfig(
            model = File(config.modelDir, config.modelOnnx).absolutePath,
            tokens = File(config.modelDir, "tokens.txt").absolutePath,
            dataDir = File(config.modelDir, "espeak-ng-data").absolutePath,
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = config.numThreads,
            debug = false,
        )
        val ttsConfig = OfflineTtsConfig(model = modelConfig)
        tts = OfflineTts(config = ttsConfig)
    }

    /** Блокирующий синтез целиком. */
    fun synthesize(text: String): FloatArray =
        tts.generate(text, sid = 0, speed = speed).samples

    /**
     * Синтез с callback по аудио-чанкам (для раннего старта воспроизведения).
     * Callback: `(FloatArray) -> Int`, 0 = продолжать, ≠0 = стоп.
     */
    fun synthesize(text: String, callback: (FloatArray) -> Int): FloatArray =
        tts.generateWithCallback(text, sid = 0, speed = speed, callback = callback).samples

    fun release() {
        tts.release()
    }
}
