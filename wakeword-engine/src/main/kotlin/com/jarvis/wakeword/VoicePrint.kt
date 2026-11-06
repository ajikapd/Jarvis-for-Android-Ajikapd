package com.jarvis.wakeword

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Образец голоса владельца: отпечатки записанных «Джарвис» и личный порог.
 * Порог считается по самим записям: насколько они похожи друг на друга, минус запас.
 */
class VoicePrint(
    val samples: List<FloatArray>,
    val threshold: Float,
) {
    /** Среднее по лучшим совпадениям: одна неудачная запись не портит итог. */
    fun score(embedding: FloatArray): Float = bestMean(embedding, samples)

    fun accepts(embedding: FloatArray): Boolean = score(embedding) >= threshold

    fun encode(): String = buildString {
        append(threshold).append('\n')
        samples.forEach { append(it.joinToString(",")).append('\n') }
    }

    companion object {
        const val SAMPLES_NEEDED = 6
        private const val BEST_MATCHES = 3
        private const val MARGIN = 0.15f
        private const val MIN_THRESHOLD = 0.30f
        private const val MAX_THRESHOLD = 0.50f

        fun enroll(samples: List<FloatArray>): VoicePrint {
            require(samples.size >= 2) { "need at least two samples" }
            val own = samples.indices.map { i ->
                bestMean(samples[i], samples.filterIndexed { j, _ -> j != i })
            }
            val threshold = (own.average().toFloat() - MARGIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
            return VoicePrint(samples, threshold)
        }

        /** null, если файл испорчен: тогда защита выключена, а не глохнет на владельце. */
        fun decode(text: String): VoicePrint? = runCatching {
            val lines = text.lines().filter { it.isNotBlank() }
            val threshold = lines.first().toFloat()
            val samples = lines.drop(1).map { line ->
                line.split(',').map { it.toFloat() }.toFloatArray()
            }
            val valid = samples.size >= 2 && samples.all { it.size == samples[0].size && it.isNotEmpty() }
            if (valid) VoicePrint(samples, threshold) else null
        }.getOrNull()

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na == 0.0 || nb == 0.0) return 0f
            return (dot / (sqrt(na) * sqrt(nb))).toFloat()
        }

        private fun bestMean(embedding: FloatArray, samples: List<FloatArray>): Float {
            val scores = samples.map { cosine(embedding, it) }.sortedDescending()
            return scores.take(BEST_MATCHES).average().toFloat()
        }
    }
}

/** Последние секунды микрофона: слово уже прозвучало к моменту срабатывания детектора. */
class RecentAudio(private val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private var pos = 0
    private var filled = 0

    fun push(samples: FloatArray) {
        for (s in samples) {
            buffer[pos] = s
            pos = (pos + 1) % capacity
        }
        filled = min(capacity, filled + samples.size)
    }

    fun snapshot(): FloatArray {
        val out = FloatArray(filled)
        val start = (pos - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = buffer[(start + i) % capacity]
        return out
    }

    fun clear() {
        pos = 0
        filled = 0
    }
}

/** Оставляет только звучащие кадры: отпечаток голоса портится от тишины и пауз. */
object VoiceTrim {
    private const val FRAME_SAMPLES = 480 // 30 мс при 16 кГц
    private const val PEAK_RATIO = 0.12f
    private const val MIN_RMS = 0.0008f
    private const val MIN_SPEECH_SAMPLES = 3200 // 0.2 с: слово занимает около 0.45 с

    /** Пустой массив, если речи слишком мало для надёжного сравнения. */
    fun speech(pcm: FloatArray): FloatArray {
        val frames = pcm.size / FRAME_SAMPLES
        if (frames == 0) return FloatArray(0)
        val rms = FloatArray(frames) { f ->
            var sum = 0.0
            for (i in 0 until FRAME_SAMPLES) {
                val s = pcm[f * FRAME_SAMPLES + i]
                sum += (s * s).toDouble()
            }
            sqrt(sum / FRAME_SAMPLES).toFloat()
        }
        val level = max((rms.maxOrNull() ?: 0f) * PEAK_RATIO, MIN_RMS)
        val voiced = (0 until frames).filter { rms[it] >= level }
        if (voiced.size * FRAME_SAMPLES < MIN_SPEECH_SAMPLES) return FloatArray(0)
        val out = FloatArray(voiced.size * FRAME_SAMPLES)
        voiced.forEachIndexed { n, f ->
            pcm.copyInto(out, n * FRAME_SAMPLES, f * FRAME_SAMPLES, (f + 1) * FRAME_SAMPLES)
        }
        return out
    }
}
