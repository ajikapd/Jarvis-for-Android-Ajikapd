package com.jarvis.core.nlp

/**
 * Финальный текст команды из трёх источников:
 * 1) streaming RU — каркас («включи песню», wake уже срезан)
 * 2) offline multilingual — часто лучше RU, но на code-switch EN→кириллица
 * 3) buffered EN — латиница названий (father, andy warhol)
 *
 * Для музыки/лаунчера: русский каркас + латинский хвост из EN.
 */
object CommandTextSelector {

    fun select(
        streamingRu: String,
        offlineMulti: String? = null,
        englishPass: String? = null,
    ): String {
        val ru = AsrPhraseNormalizer.normalize(streamingRu.trim())
        val multi = offlineMulti?.trim()?.let { AsrPhraseNormalizer.normalize(it) }.orEmpty()
        val enRaw = englishPass?.trim().orEmpty()
        val enLatin = extractLatinPhrase(enRaw)

        // Музыка/лаунчер: русский каркас + латинский объект из EN — выше multi,
        // т.к. NeMo на code-switch часто даёт кириллицу или битую латиницу.
        if (enLatin.isNotBlank() && isObjectCommand(ru)) {
            val skeleton = when {
                looksLikeCommand(ru) -> ru
                looksLikeCommand(multi) -> multi
                else -> ru
            }
            val merged = RuEnTextMerger.merge(skeleton, enLatin)
            if (merged.any { it in 'a'..'z' }) return merged
        }

        val base = when {
            multi.isNotBlank() && looksLikeCommand(multi) -> multi
            ru.isNotBlank() -> ru
            multi.isNotBlank() -> multi
            else -> ""
        }

        if (enLatin.isNotBlank() && isObjectCommand(base)) {
            val merged = RuEnTextMerger.merge(base, enLatin)
            if (merged.any { it in 'a'..'z' }) return merged
        }

        if (enLatin.isNotBlank() && base.isBlank()) return enLatin

        if (multi.any { it in 'a'..'z' }) {
            return preferWithRuVerb(ru, multi)
        }

        if (enLatin.isNotBlank() && base.isNotBlank() && !base.any { it in 'a'..'z' }) {
            val merged = RuEnTextMerger.merge(base, enLatin)
            if (merged.any { it in 'a'..'z' }) return merged
        }

        return base.ifBlank { ru }.ifBlank { enLatin }
    }

    /** Только латинские токены из EN-прохода (отбрасываем мусор от русской речи). */
    fun extractLatinPhrase(enRaw: String): String {
        val cleaned = enRaw.lowercase()
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return ""
        val words = cleaned.split(" ").filter { w ->
            w.length > 1 &&
                w.any { it in 'a'..'z' } &&
                w !in EN_NOISE
        }
        return words.joinToString(" ")
    }

    private fun preferWithRuVerb(ru: String, other: String): String {
        val ruLower = ru.lowercase()
        val otherLower = other.lowercase()
        val hasRuVerb = COMMAND_VERBS.any { ruLower.contains(it) }
        val hasOtherVerb = COMMAND_VERBS.any { otherLower.contains(it) }
        if (hasRuVerb && !hasOtherVerb) return RuEnTextMerger.merge(ru, other)
        return other
    }

    private fun isObjectCommand(text: String): Boolean {
        val t = text.lowercase()
        return COMMAND_VERBS.any { t.contains(it) } ||
            t.contains("песн") || t.contains("трек") || t.contains("альбом") ||
            t.contains("музык") || t.contains("открой") || t.contains("запусти")
    }

    private fun looksLikeCommand(text: String): Boolean {
        val t = text.lowercase()
        return COMMAND_VERBS.any { t.contains(it) } ||
            t.contains("песн") || t.contains("трек") || t.contains("погод")
    }

    private val COMMAND_VERBS = listOf(
        "включи", "выключи", "открой", "закрой", "поставь", "запусти",
        "найди", "покажи", "расскажи", "play", "open", "turn",
    )

    /** Слова, которые EN-модель часто галлюцинирует на русской речи. */
    private val EN_NOISE = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "my", "me",
        "i", "you", "we", "it", "is", "are", "was", "be", "this", "that",
        "please", "yeah", "yes", "no", "oh", "uh", "um", "ah",
        "he", "she", "his", "her", "they", "them", "their",
        "thank", "thanks", "okay", "ok", "so", "just", "like",
    )
}
