package com.jarvis.core.agent

/** Задача, которую приложение выполняет штатно: без модели и без нажатий. */
sealed class AgentShortcut {
    data class YouTubeSearch(val query: String) : AgentShortcut()
    data class Setting(val name: String) : AgentShortcut()
}

/** Угадывание по экрану нужно только там, где нет прямого пути. */
object AgentShortcuts {
    private val YOUTUBE = Regex("ютуб|ютьюб|youtube")
    private val AFTER_SEARCH = Regex("(?:найди|найти|поищи|ищи|включи|покажи)\\s+(?:мне\\s+)?(?:видео\\s+|ролик\\s+|ролики\\s+)?(?:про\\s+|о\\s+|об\\s+)?(.+)$")
    private val YOUTUBE_WORD = Regex("\\s*(?:на|в)\\s+(?:ютубе|ютьюбе|youtube)\\s*")
    private val SETTINGS = Regex("настрой")
    private val MORE_ACTIONS = Regex("\\bи\\s+(?:включи|выключи|открой|запусти|нажми|напиши|отправь|поставь)\\b")
    private val SECTIONS = linkedMapOf(
        "точк" to "hotspot",
        "блютуз" to "bluetooth",
        "блютус" to "bluetooth",
        "bluetooth" to "bluetooth",
        "вайфа" to "wifi",
        "вай фа" to "wifi",
        "wifi" to "wifi",
        "батаре" to "battery",
        "звук" to "sound",
        "экран" to "display",
        "памят" to "storage",
    )

    fun match(goal: String): AgentShortcut? {
        val text = goal.lowercase().trim()
        if (MORE_ACTIONS.containsMatchIn(text)) return null
        if (YOUTUBE.containsMatchIn(text)) {
            val query = AFTER_SEARCH.find(text)?.groupValues?.get(1)?.replace(YOUTUBE_WORD, " ")?.trim()
            return query?.takeIf { it.isNotEmpty() && !YOUTUBE.matches(it) }?.let { AgentShortcut.YouTubeSearch(it) }
        }
        if (!SETTINGS.containsMatchIn(text)) return null
        return SECTIONS.entries.firstOrNull { it.key in text }?.let { AgentShortcut.Setting(it.value) }
    }
}
