package com.jarvis.core.nlp

import kotlin.math.abs
import kotlin.math.max

/**
 * Сопоставление ASR-запроса (часто кириллическая фонетика EN) с названиями треков.
 * RU-only zipformer отдаёт «фазер» вместо «father» — сравниваем через
 * нормализацию, транслит и Левенштейна, а не через сырой contains.
 */
object MusicQueryMatcher {

    data class Scored<T>(val item: T, val score: Int, val label: String)

    /** Варианты строки для поиска в каталоге: оригинал, нормализованный, латиница. */
    fun queryVariants(query: String): List<String> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val normalized = AsrPhraseNormalizer.normalize(raw)
        val latin = raw.split(Regex("\\s+"))
            .joinToString(" ") { token ->
                val mapped = AsrPhraseNormalizer.normalize(token)
                if (mapped != token.lowercase() && mapped.any { it in 'a'..'z' }) mapped
                else Transliterator.toLatin(token).replace(Regex("[^\\p{L}\\p{N}]+"), "")
            }
            .trim()
        return listOf(raw, normalized, latin)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    fun score(query: String, title: String, subtitle: String = ""): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        val titleScore = scoreAgainst(q, title)
        val subScore = if (subtitle.isBlank()) 0 else scoreAgainst(q, subtitle)
        val combined = scoreAgainst(q, "$title $subtitle".trim())
        // Бонус, если все токены запроса покрыты title+subtitle
        val cover = tokenCoverage(q, "$title $subtitle")
        return maxOf(titleScore, subScore, combined, cover)
    }

    fun <T> bestMatch(
        items: Iterable<T>,
        query: String,
        labelOf: (T) -> Pair<String, String>,
        minScore: Int = MIN_ACCEPT,
    ): Scored<T>? {
        val variants = queryVariants(query)
        if (variants.isEmpty()) return null
        var best: Scored<T>? = null
        for (item in items) {
            val (title, sub) = labelOf(item)
            if (title.isBlank() && sub.isBlank()) continue
            val s = variants.maxOf { score(it, title, sub) }
            if (s < minScore) continue
            val label = when {
                title.isNotBlank() && sub.isNotBlank() -> "$sub — $title"
                title.isNotBlank() -> title
                else -> sub
            }
            if (best == null || s > best.score) {
                best = Scored(item, s, label)
            }
        }
        return best
    }

    private fun scoreAgainst(query: String, candidate: String): Int {
        val qRaw = query.lowercase().trim()
        val cRaw = candidate.lowercase().trim()
        if (qRaw.isEmpty() || cRaw.isEmpty()) return 0
        if (qRaw == cRaw) return 100

        val q = Transliterator.compareKey(query)
        val c = Transliterator.compareKey(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 98
        if (c.startsWith(q) && q.length >= 3) return 90
        if (significantContains(c, q)) return 82
        if (significantContains(q, c)) return 72

        // Фонетическая близость целых ключей («fazer»↔«father»)
        val sim = similarity(q, c)
        if (sim >= 0.82) return (88 * sim).toInt()
        if (sim >= 0.70) return (75 * sim).toInt()

        // По токенам: каждый токен запроса должен найтись в кандидате
        val qTokens = tokens(query).filterNot { it in STOPWORDS }
        val cTokens = tokens(candidate).filterNot { it in STOPWORDS }
        if (qTokens.isEmpty()) return 0
        var hit = 0
        var fuzzyHit = 0
        for (qt in qTokens) {
            val qk = Transliterator.compareKey(qt)
            if (qk.length < 2) continue
            val exact = cTokens.any { ct ->
                val ck = Transliterator.compareKey(ct)
                ck == qk || significantContains(ck, qk) || significantContains(qk, ck)
            }
            if (exact) {
                hit++
                continue
            }
            val close = cTokens.any { similarity(qk, Transliterator.compareKey(it)) >= 0.78 }
            if (close) fuzzyHit++
        }
        val need = qTokens.count { Transliterator.compareKey(it).length >= 2 }
        if (need == 0) return 0
        if (hit == need) return 86
        if (hit + fuzzyHit == need && hit >= 1) return 70
        if (hit + fuzzyHit == need) return 58
        if (hit > 0 && hit + fuzzyHit >= (need + 1) / 2) {
            return 40 + 15 * hit
        }
        return 0
    }

    private fun tokenCoverage(query: String, haystack: String): Int {
        val qTokens = tokens(query)
            .filterNot { it in STOPWORDS }
            .map { Transliterator.compareKey(it) }
            .filter { it.length >= 2 }
        if (qTokens.isEmpty()) return 0
        val hTokens = tokens(haystack).filterNot { it in STOPWORDS }.map { Transliterator.compareKey(it) }
        val h = Transliterator.compareKey(haystack)
        val covered = qTokens.count { t ->
            significantContains(h, t) || hTokens.any { similarity(t, it) >= 0.78 || it == t }
        }
        if (covered == qTokens.size) return 84
        if (covered >= (qTokens.size + 1) / 2) return 45 + 10 * covered
        return 0
    }

    /** «father».contains(«the») — ложное; короткие/малозначимые подстроки не считаем. */
    private fun significantContains(longer: String, shorter: String): Boolean {
        if (shorter.length < 4) return false
        if (!longer.contains(shorter)) return false
        // подстрока должна быть существенной долей длинной строки
        return shorter.length * 100 / longer.length >= 55
    }

    private fun tokens(value: String): List<String> =
        TOKEN_REGEX.findAll(value.lowercase()).map { it.value }.filter { it.length > 1 }.toList()

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (abs(a.length - b.length) > max(3, max(a.length, b.length) / 2)) return 0.0
        val dist = levenshtein(a, b)
        return 1.0 - dist.toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = b.length
        val dp = IntArray(m + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..m) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                prev = tmp
            }
        }
        return dp[m]
    }

    const val MIN_ACCEPT = 55
    const val MIN_SEARCH = 55
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "my", "me",
        "и", "на", "в", "с", "по", "из", "для", "это", "я", "ты",
    )
}
