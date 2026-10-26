package com.jarvis.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class SherpaAsrSession(
    modelDir: String,
    private val spec: AsrModelSpec = AsrModelSpec.RU_STREAMING,
) : AsrSession {

    private val lock = Any()
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream
    private var released = false

    init {
        require(File(modelDir).isDirectory) { "Model dir not found: $modelDir" }
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
            ),
            // rule2: тишина после речи. Раньше 0.9с резало «напиши … в телеграмме»
            // на два чанка; 1.6с + incomplete-hold склеивают длинные команды.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.0f, 0.0f),
                rule2 = EndpointRule(true, 1.6f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 25.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer.createStream()
    }

    override fun processChunk(samples: FloatArray): AsrChunkResult =
        synchronized(lock) {
            if (released) AsrChunkResult(text = "", isEndpoint = false) else run {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }
                val isEndpoint = recognizer.isEndpoint(stream)
                val text = recognizer.getResult(stream).text
                AsrChunkResult(text = text, isEndpoint = isEndpoint)
            }
        }

    override fun reset() {
        synchronized(lock) {
            if (!released) recognizer.reset(stream)
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            stream.release()
            recognizer.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        /** @deprecated используй [AsrModelSpec.RU_STREAMING.dirName] */
        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16"
    }
}
