package com.jarvis.core.nlp

/**
 * Нормализация русского текста перед разбором: нижний регистр,
 * удаление пунктуации/кавычек, схлопывание пробелов.
 */
object RussianNormalizer {

    private val punctuation = Regex("[«»\"'….,!?]")
    private val nonAlpha = Regex("[^\\p{L}\\p{N}\\s-]")
    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): String = raw
        .lowercase()
        .replace(punctuation, "")
        .replace(nonAlpha, " ")
        .trim()
        .replace(whitespace, " ")

    fun normalizeWords(raw: String): List<String> =
        normalize(raw).split(' ').filter { it.isNotBlank() }
}