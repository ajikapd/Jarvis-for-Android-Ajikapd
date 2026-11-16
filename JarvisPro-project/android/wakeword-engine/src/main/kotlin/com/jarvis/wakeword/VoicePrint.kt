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

    /** Тот же порог, но сравнение ещё и с выученными образцами. */
    fun with(extra: List<FloatArray>): VoicePrint = VoicePrint(samples + extra, threshold)

    fun encode(): String = buildString {
        append(threshold).append('\n')
        samples.forEach { append(it.joinToString(",")).append('\n') }
    }

    companion object {
        const val SAMPLES_NEEDED = 10
        private const val BEST_MATCHES = 3
        private const val MARGIN = 0.15f
        private const val MIN_THRESHOLD = 0.30f
        private const val MAX_THRESHOLD = 0.50f

        fun enroll(samples: List<FloatArray>): VoicePrint = enrollGroups(samples.map { listOf(it) })

        /** Куски одной записи сверяются только с другими записями: иначе порог завышен. */
        fun enrollGroups(groups: List<List<FloatArray>>): VoicePrint {
            require(groups.size >= 2) { "need at least two samples" }
            val own = groups.indices.flatMap { i ->
                val others = groups.filterIndexed { j, _ -> j != i }.flatten()
                groups[i].map { bestMean(it, others) }
            }
            val threshold = (own.average().toFloat() - MARGIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
            return VoicePrint(groups.flatten(), threshold)
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

/**
 * Образцы, выученные на командах владельца. Ограниченная очередь: старые вытесняются,
 * а записанный образец остаётся, поэтому голос в памяти не может уплыть к чужому.
 */
class VoiceMemory(private val capacity: Int, initial: List<FloatArray> = emptyList()) {
    private val items = ArrayDeque(initial.takeLast(capacity))

    val samples: List<FloatArray> get() = items.toList()
    val size: Int get() = items.size

    fun add(embedding: FloatArray) {
        items.addLast(embedding)
        while (items.size > capacity) items.removeFirst()
    }

    fun encode(): String = items.joinToString("\n") { it.joinToString(",") }

    companion object {
        /** Испорченные строки пропускаются: память лишь усиливает записанный образец. */
        fun decode(text: String, capacity: Int): VoiceMemory = VoiceMemory(
            capacity,
            text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { line.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
            },
        )
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
    private const val WINDOW_SAMPLES = 24_000 // 1.5 с
    private const val HOP_SAMPLES = 12_000
    private const val MAX_WINDOWS = 6

    /** Пустой массив, если речи слишком мало для надёжного сравнения. */
    fun speech(pcm: FloatArray, minSamples: Int = MIN_SPEECH_SAMPLES): FloatArray {
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
        if (voiced.size * FRAME_SAMPLES < minSamples) return FloatArray(0)
        val out = FloatArray(voiced.size * FRAME_SAMPLES)
        voiced.forEachIndexed { n, f ->
            pcm.copyInto(out, n * FRAME_SAMPLES, f * FRAME_SAMPLES, (f + 1) * FRAME_SAMPLES)
        }
        return out
    }

    /** Куски речи: при звуке из видео поверх голоса хотя бы один остаётся чистым. */
    fun windows(speech: FloatArray): List<FloatArray> {
        if (speech.size <= WINDOW_SAMPLES) return if (speech.isEmpty()) emptyList() else listOf(speech)
        val last = speech.size - WINDOW_SAMPLES
        val hop = max(HOP_SAMPLES, last / (MAX_WINDOWS - 1))
        return (0..last step hop).map { speech.copyOfRange(it, it + WINDOW_SAMPLES) }
    }
}
