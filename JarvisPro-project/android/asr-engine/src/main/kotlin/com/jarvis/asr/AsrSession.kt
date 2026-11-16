package com.jarvis.asr

data class AsrChunkResult(val text: String, val isEndpoint: Boolean)

interface AsrSession {
    fun processChunk(samples: FloatArray): AsrChunkResult
    fun reset()
    fun release()
}