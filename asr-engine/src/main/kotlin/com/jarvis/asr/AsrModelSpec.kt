package com.jarvis.asr

/**
 * Описание streaming transducer-модели sherpa-onnx.
 * RU small-vosk — zipformer2; EN 20M/2023 — классический zipformer (modelType пустой).
 */
data class AsrModelSpec(
    val dirName: String,
    val modelType: String,
    val encoderFile: String = "encoder.int8.onnx",
    val decoderFile: String = "decoder.onnx",
    val joinerFile: String = "joiner.int8.onnx",
    val tokensFile: String = "tokens.txt",
) {
    fun requiredFiles(): List<String> = listOf(encoderFile, decoderFile, joinerFile, tokensFile)

    companion object {
        val RU_STREAMING = AsrModelSpec(
            dirName = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
            modelType = "zipformer2",
        )
        val EN_STREAMING = AsrModelSpec(
            dirName = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            modelType = "",
        )
    }
}
