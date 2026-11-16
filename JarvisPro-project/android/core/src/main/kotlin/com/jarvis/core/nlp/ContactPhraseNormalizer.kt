package com.jarvis.core.nlp

/**
 * Нормализация обращения к контакту из речи в формы для поиска в Telegram.
 *
 * «любимой жене» → поиск «жена» / «любимая жена», а не дословное «любимой жене».
 */
object ContactPhraseNormalizer {

    /** Каноническая фраза для слота chat (именительный). */
    fun canonical(spoken: String): String {
        val forms = searchQueries(spoken)
        return forms.firstOrNull().orEmpty().ifBlank { spoken.trim() }
    }

    /**
     * Варианты для UI-поиска: сначала короткое ядро («жена»), потом с эпитетом.
     * Дословную речь не ставим первой — Telegram так редко индексирует.
     */
    fun searchQueries(spoken: String): List<String> {
        val raw = spoken.trim()
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()

        val honorifics = mutableListOf<String>()
        val core = mutableListOf<String>()
        for (p in parts) {
            if (p.lowercase() in HONORIFICS) honorifics += toNominativeAdjective(p)
            else core += toNominativeNoun(p)
        }
        if (core.isEmpty() && honorifics.isNotEmpty()) {
            // только «любимая» — бесполезно; вернём как есть
            return listOf(raw)
        }

        val corePhrase = core.joinToString(" ").trim()
        val withHonorific = (honorifics + core).joinToString(" ").trim()
        val last = core.lastOrNull().orEmpty()

        return buildList {
            if (corePhrase.isNotBlank()) add(corePhrase)
            if (last.isNotBlank() && last != corePhrase) add(last)
            if (withHonorific.isNotBlank() && withHonorific != corePhrase) add(withHonorific)
            // запасной дословный вариант в конце
            if (raw != corePhrase && raw != withHonorific) add(raw)
        }.distinct()
    }

    fun toNominativeNoun(word: String): String {
        val lower = word.lowercase()
        KNOWN_OBLIQUE[lower]?.let { return matchCase(word, it) }

        when {
            lower.endsWith("у") && lower.length >= 4 ->
                return matchCase(word, lower.dropLast(1))
            lower.endsWith("ю") && lower.length >= 4 ->
                return matchCase(word, lower.dropLast(1) + "я")
            lower.endsWith("е") && lower.length in 4..10 &&
                !lower.endsWith("ое") && !lower.endsWith("ее") &&
                !lower.endsWith("ие") && !lower.endsWith("ье") -> {
                val stem = lower.dropLast(1)
                if (stem.endsWith("ь")) return matchCase(word, stem.dropLast(1) + "я")
                return matchCase(word, stem + "а")
            }
            lower.endsWith("ой") || lower.endsWith("ей") ->
                return toNominativeAdjective(word)
        }
        return word
    }

    fun toNominativeAdjective(word: String): String {
        val lower = word.lowercase()
        return when {
            lower.endsWith("ой") && lower.length > 3 ->
                matchCase(word, lower.dropLast(2) + "ая") // любимой → любимая
            lower.endsWith("ей") && lower.length > 3 ->
                matchCase(word, lower.dropLast(2) + "ая")
            lower.endsWith("ому") && lower.length > 4 ->
                matchCase(word, lower.dropLast(3) + "ый")
            lower.endsWith("ему") && lower.length > 4 ->
                matchCase(word, lower.dropLast(3) + "ий")
            else -> word
        }
    }

    private fun matchCase(sample: String, value: String): String {
        if (sample.isEmpty() || value.isEmpty()) return value
        return if (sample[0].isUpperCase()) {
            value.replaceFirstChar { it.uppercase() }
        } else {
            value
        }
    }

    private val HONORIFICS = setOf(
        "любимой", "любимый", "любимая", "любимого", "любимую",
        "мой", "моя", "моей", "моему", "моего", "мою",
        "дорогой", "дорогая", "дорогому", "дорогую",
        "милый", "милая", "милому", "милую",
        "родной", "родная", "родному",
    )

    private val KNOWN_OBLIQUE = mapOf(
        "жене" to "жена", "женой" to "жена", "жены" to "жена", "жену" to "жена",
        "маме" to "мама", "мамой" to "мама", "мамы" to "мама", "маму" to "мама", "мами" to "мама",
        "папе" to "папа", "папой" to "папа", "папы" to "папа", "папу" to "папа",
        "брату" to "брат", "брата" to "брат", "братом" to "брат",
        "сестре" to "сестра", "сестру" to "сестра", "сестрой" to "сестра",
        "другу" to "друг", "друга" to "друг", "другом" to "друг",
        "подруге" to "подруга", "подругу" to "подруга", "подругой" to "подруга",
        "мужу" to "муж", "мужа" to "муж", "мужем" to "муж",
        "сыну" to "сын", "сына" to "сын", "сыном" to "сын",
        "дочери" to "дочь", "дочку" to "дочь", "дочерью" to "дочь",
        "бабушке" to "бабушка", "бабушку" to "бабушка",
        "дедушке" to "дедушка", "дедушку" to "дедушка",
        "коллеге" to "коллега", "коллегу" to "коллега",
        "начальнику" to "начальник", "начальника" to "начальник",
        "избранному" to "Избранное", "избранной" to "Избранное",
    )
}
