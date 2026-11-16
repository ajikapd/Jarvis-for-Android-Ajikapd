package com.jarvis.core.nlp

/**
 * Склейка выходов RU и EN streaming-ASR в одну команду.
 *
 * RU-модель хорошо слышит «включи / открой / джарвис», но английские названия
 * отдаёт фонетической кириллицей. EN-модель наоборот: латиница в названиях,
 * русский каркас команды — мусор. Берём каркас из RU и объект из EN.
 */
object RuEnTextMerger {

    fun merge(ruRaw: String, enRaw: String): String {
        val ru = AsrPhraseNormalizer.normalize(ruRaw.trim())
        val en = cleanupEn(enRaw)
        if (ru.isBlank()) return en.ifBlank { ruRaw.trim() }
        if (en.isBlank()) return ru

        val ruLower = ru.lowercase()
        val enWords = en.split(Regex("\\s+")).filter { it.length > 1 && it !in EN_STOP }
        if (enWords.isEmpty()) return ru

        // Музыка: «включи песню …» → суффикс из EN
        if (MUSIC_MARKERS.any { ruLower.contains(it) }) {
            val prefix = takeThroughMarker(ru, MUSIC_PREFIX_TOKENS)
            val enTitle = enWords.joinToString(" ")
            return if (prefix.isNotBlank()) "$prefix $enTitle".trim() else enTitle
        }

        // Лаунчер: «открой / запусти …»
        if (OPEN_VERBS.any { ruLower.startsWith(it) || ruLower.contains(" $it ") }) {
            val prefix = takeThroughMarker(ru, OPEN_VERBS)
            val enApp = enWords.joinToString(" ")
            return if (prefix.isNotBlank()) "$prefix $enApp".trim() else "$ru $enApp".trim()
        }

        // Если EN дал заметную латиницу, а в RU после глагола только «ослышки» —
        // подмешиваем EN-токены в хвост.
        if (enWords.size >= 1 && hasLikelyEnPhonetics(ruLower)) {
            val verbs = ru.split(Regex("\\s+")).takeWhile { it.lowercase() in COMMANDISH }
            if (verbs.isNotEmpty()) {
                return (verbs + enWords).joinToString(" ")
            }
        }

        // Иначе оставляем нормализованный RU (словари AsrPhraseNormalizer)
        return ru
    }

    private fun cleanupEn(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun takeThroughMarker(text: String, markers: Set<String>): String {
        val tokens = text.split(Regex("\\s+"))
        val idx = tokens.indexOfLast { t ->
            val l = t.lowercase()
            markers.any { m -> l == m || l.startsWith(m) }
        }
        if (idx < 0) return tokens.take(1).joinToString(" ")
        return tokens.take(idx + 1).joinToString(" ")
    }

    private fun hasLikelyEnPhonetics(ru: String): Boolean {
        // грубый эвристический сигнал: после русского глагола есть токены без типичных
        // русских окончаний команд / бытовых слов
        val tail = ru.split(Regex("\\s+")).dropWhile { it in COMMANDISH }
        if (tail.isEmpty()) return false
        return tail.any { t ->
            t.any { ch -> ch in 'a'..'z' } ||
                PHONETIC_HINTS.any { h -> t.contains(h) }
        }
    }

    private val MUSIC_MARKERS = listOf("песн", "трек", "музык", "альбом", "исполнит", "артист", "композиц")
    private val MUSIC_PREFIX_TOKENS = setOf(
        "включи", "включить", "поставь", "поставьте", "запусти", "запустить",
        "песню", "песня", "песенку", "трек", "музыку", "музыка",
        "альбом", "альбома", "исполнителя", "исполнитель", "артиста", "артист",
        "композицию", "мне",
    )
    private val OPEN_VERBS = setOf("открой", "открыть", "откройте", "запусти", "запустить")
    private val COMMANDISH = MUSIC_PREFIX_TOKENS + OPEN_VERBS + setOf(
        "выключи", "закрой", "сделай", "найди", "покажи", "джарвис",
    )
    private val EN_STOP = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "my", "me",
        "please", "play", "song", "music", "open", "start", "turn", "on", "off",
    )
    private val PHONETIC_HINTS = listOf(
        "фаз", "ворх", "уорх", "энд", "боге", "рэпс", "инс", "споти", "тейл", "дрейк",
    )
}
