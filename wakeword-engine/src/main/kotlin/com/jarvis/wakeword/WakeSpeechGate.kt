package com.jarvis.wakeword

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Проверка «в момент срабатывания действительно звучала речь»: отсекает ложные срабатывания
 * на тишине и щелчках. Порог мягкий, тихое слово с 2–3 м (около −47 дБ) проходит.
 */
class WakeSpeechGate {
    private val recent = FloatArray(WINDOW_CHUNKS)
    private var pos = 0
    private var filled = 0
    private var floor = INITIAL_FLOOR

    fun onChunk(samples: FloatArray) {
        if (samples.isEmpty()) return
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        val rms = sqrt(sum / samples.size).toFloat()
        floor = if (rms < floor) max(rms, MIN_FLOOR) else floor * FLOOR_RISE
        recent[pos] = rms
        pos = (pos + 1) % WINDOW_CHUNKS
        if (filled < WINDOW_CHUNKS) filled++
    }

    /** Число чанков окна, звучавших заметно громче шумового пола. */
    fun voicedChunks(): Int {
        val threshold = max(floor * VOICED_RATIO, MIN_VOICED_RMS)
        var voiced = 0
        for (i in 0 until filled) if (recent[i] >= threshold) voiced++
        return voiced
    }

    fun speechPresent(): Boolean = voicedChunks() >= MIN_VOICED_CHUNKS

    val noiseFloor: Float get() = floor

    companion object {
        /** 1.5 с при чанках 100 мс. */
        private const val WINDOW_CHUNKS = 15
        private const val MIN_VOICED_CHUNKS = 3
        private const val VOICED_RATIO = 2.5f
        private const val MIN_VOICED_RMS = 0.0008f
        private const val INITIAL_FLOOR = 0.001f
        private const val MIN_FLOOR = 1e-5f
        /** Пол растёт вдвое примерно за 14 с: постоянный шум не считается речью. */
        private const val FLOOR_RISE = 1.005f
    }
}
