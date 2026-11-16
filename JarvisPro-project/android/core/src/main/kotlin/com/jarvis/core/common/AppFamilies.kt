package com.jarvis.core.common

/**
 * Неофициальные клиенты, которые люди называют именем оригинала:
 * «открой телеграм» при установленном AyuGram должно открыть AyuGram.
 */
object AppFamilies {
    /** Официальные клиенты первыми. */
    val TELEGRAM = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "org.telegram.plus",
        "com.radolyn.ayugram",
        "com.exteragram.messenger",
        "tw.nekomimi.nekogram",
        "org.telegram.mdgram",
        "uz.unnarsx.cherrygram",
    )

    private val TELEGRAM_WORDS = setOf("телеграм", "телеграмм", "телега", "телегу", "тг", "telegram", "телеграме", "телеграмма")

    /** Пакеты семьи по сказанному названию; пусто, если название не семейное. */
    fun packagesFor(query: String): List<String> {
        val q = query.lowercase().trim().removePrefix("в ").trim()
        return if (q in TELEGRAM_WORDS) TELEGRAM else emptyList()
    }
}
