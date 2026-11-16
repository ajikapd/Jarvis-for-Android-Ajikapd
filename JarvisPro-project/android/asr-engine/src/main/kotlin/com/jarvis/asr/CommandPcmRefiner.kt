package com.jarvis.asr

/**
 * Политика второго прохода: минимум декодов, максимум пользы для RU+EN.
 *
 * Быстрый путь: 1× NeMo(full).
 * EN-путь (только music/open): + EN(tail) и при необходимости NeMo(tail).
 * Оконный скан — только как явный fallback (дорого).
 */
object CommandPcmRefiner {

    /** Хвост для EN/multi @16kHz — хватает на название, без лишнего. */
    const val TAIL_SAMPLES = 16_000 + 8_000 // 1.5 с

    fun needsLatinRefine(streamingRu: String): Boolean {
        val t = streamingRu.lowercase()
        if (t.isBlank()) return false
        if (MUSIC_HINTS.any { t.contains(it) }) return true
        if (OPEN_HINTS.any { t.contains(it) }) {
            // «открой»/«запусти» с объектом — возможен EN-бренд
            val words = t.split(Regex("\\s+")).filter { it.length > 1 }
            return words.size >= 2
        }
        return false
    }

    /** Multi уже дал латиницу в хвосте — второй EN не нужен. */
    fun hasUsefulLatin(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        return latinScore(t) >= 4
    }

    fun bestLatinHint(vararg candidates: String?): String {
        var best = ""
        var bestScore = 0
        for (raw in candidates) {
            val t = raw?.trim().orEmpty()
            if (t.isBlank()) continue
            val score = latinScore(t)
            if (score > bestScore) {
                bestScore = score
                best = t
            }
        }
        return best
    }

    fun latinScore(text: String): Int {
        var latin = 0
        var cyr = 0
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                ch in '\u0400'..'\u04FF' -> cyr++
            }
        }
        if (latin == 0) return 0
        return latin * 2 - cyr
    }

    fun tailOf(pcm: FloatArray, maxSamples: Int = TAIL_SAMPLES): FloatArray {
        if (pcm.size <= maxSamples) return pcm
        return pcm.copyOfRange(pcm.size - maxSamples, pcm.size)
    }

    /**
     * Дорогой fallback: скользящие окна NeMo. Не вызывать в hot path.
     */
    fun scanLatinWindows(
        pcm: FloatArray,
        decode: (FloatArray) -> String,
        windowSamples: Int = WINDOW_SAMPLES,
        hopSamples: Int = HOP_SAMPLES,
        maxWindows: Int = 4,
    ): String {
        if (pcm.isEmpty()) return ""
        if (pcm.size <= windowSamples) {
            return decode(pcm).takeIf { latinScore(it) > 0 }.orEmpty()
        }
        val hits = ArrayList<String>(maxWindows)
        var i = 0
        var n = 0
        while (i < pcm.size && n < maxWindows) {
            val end = minOf(i + windowSamples, pcm.size)
            if (end - i >= windowSamples / 2) {
                val t = decode(pcm.copyOfRange(i, end))
                if (latinScore(t) >= 3) hits.add(t)
                n++
            }
            if (end >= pcm.size) break
            i += hopSamples
        }
        return bestLatinHint(*hits.toTypedArray())
    }

    private val MUSIC_HINTS = listOf(
        "песн", "песен", "трек", "музык", "альбом", "исполнит", "артист", "композиц",
    )
    private val OPEN_HINTS = listOf("открой", "открыть", "запусти", "запустить")

    private const val WINDOW_SAMPLES = 16_000 + 8_000 // 1.5 с
    private const val HOP_SAMPLES = 16_000 // 1 с
}
