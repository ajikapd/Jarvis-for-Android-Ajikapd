package com.jarvis.core.nlp

import kotlin.random.Random

/**
 * Реплики написаны с «сэр», а слышать его в каждом ответе утомительно.
 * Обращение остаётся в одном ответе из двух-четырёх, вразнобой, как в живой речи.
 */
class AddressRhythm(private val random: Random = Random.Default) {
    private var pause = 0
    private var keep = true

    /** Вызывается один раз на ответ, даже если он произносится несколькими фразами. */
    fun nextReply() {
        keep = pause == 0
        pause = if (keep) random.nextInt(MIN_PAUSE, MAX_PAUSE + 1) else pause - 1
    }

    fun apply(text: String): String = if (keep) text else strip(text)

    companion object {
        private const val MIN_PAUSE = 1
        private const val MAX_PAUSE = 3
        private val AT_START = Regex("^\\s*сэр\\s*[,.!]?\\s*", RegexOption.IGNORE_CASE)
        private val INSIDE = Regex("[,\\s]+сэр(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

        fun strip(text: String): String {
            val out = INSIDE.replace(AT_START.replace(text, ""), "").trim()
            if (out.none { it.isLetterOrDigit() }) return text
            return out.replaceFirstChar { it.uppercase() }
        }
    }
}
