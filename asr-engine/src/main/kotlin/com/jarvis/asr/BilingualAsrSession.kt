package com.jarvis.asr

import android.util.Log
import com.jarvis.core.nlp.AsrPhraseNormalizer
import com.jarvis.core.nlp.RuEnTextMerger

/**
 * Параллельный RU+EN streaming ASR.
 * Endpoint и wake берутся с RU-потока; финальный текст — склейка [RuEnTextMerger].
 * Если EN-модель не загрузилась, работает как обычный RU.
 */
class BilingualAsrSession(
    private val ru: AsrSession,
    private val en: AsrSession?,
) : AsrSession {

    private val lock = Any()

    override fun processChunk(samples: FloatArray): AsrChunkResult =
        synchronized(lock) {
            val ruResult = ru.processChunk(samples)
            val enText = en?.processChunk(samples)?.text.orEmpty()
            val merged = if (en == null || enText.isBlank()) {
                AsrPhraseNormalizer.normalize(ruResult.text)
            } else {
                val out = RuEnTextMerger.merge(ruResult.text, enText)
                if (ruResult.isEndpoint && (ruResult.text.isNotBlank() || enText.isNotBlank())) {
                    Log.i(TAG, "ru=\"${ruResult.text}\" en=\"$enText\" -> \"$out\"")
                }
                out
            }
            AsrChunkResult(text = merged, isEndpoint = ruResult.isEndpoint)
        }

    override fun reset() {
        synchronized(lock) {
            ru.reset()
            en?.reset()
        }
    }

    override fun release() {
        synchronized(lock) {
            ru.release()
            en?.release()
        }
    }

    companion object {
        private const val TAG = "Jarvis.Asr"
    }
}
