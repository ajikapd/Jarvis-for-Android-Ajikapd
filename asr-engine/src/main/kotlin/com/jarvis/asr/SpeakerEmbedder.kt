package com.jarvis.asr

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/** Отпечаток голоса из куска речи 16 кГц. */
class SpeakerEmbedder(modelPath: String) : AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = modelPath, numThreads = 2),
    )

    /** null, если речи не хватило модели. */
    fun embed(pcm: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(pcm, SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
