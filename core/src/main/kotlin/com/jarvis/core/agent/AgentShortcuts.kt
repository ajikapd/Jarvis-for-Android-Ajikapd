package com.jarvis.core.agent

/** Задача, которую приложение выполняет штатно: без модели и без нажатий. */
sealed class AgentShortcut {
    data class YouTubeSearch(val query: String, val playFirst: Boolean = false) : AgentShortcut()
    data class Setting(val name: String) : AgentShortcut()
    data class MapSearch(val place: String) : AgentShortcut()
    data class WebSearch(val query: String) : AgentShortcut()
    /** Номер только набирается, вызов не начинается. */
    data class DialDraft(val number: String) : AgentShortcut()
    data class Timer(val seconds: Int) : AgentShortcut()
    data class CalculatorSum(val left: Int, val right: Int) : AgentShortcut()
    /** Поиск в браузере и открытие первого обычного результата. */
    data class OpenFirstResult(val query: String) : AgentShortcut()
}

/** Угадывание по экрану нужно только там, где нет прямого пути. */
object AgentShortcuts {
    private val YOUTUBE = Regex("ютуб|ютьюб|youtube")
    private val AFTER_SEARCH = Regex("(?:найди|найти|поищи|ищи|покажи)\\s+(?:мне\\s+)?(?:видео\\s+|ролик\\s+|ролики\\s+)?(?:про\\s+|о\\s+|об\\s+)?(.+)$")
    private val YOUTUBE_WORD = Regex("\\s*(?:на|в)\\s+(?:ютубе|ютьюбе|youtube)\\s*")
    private val SETTINGS = Regex("настрой")
    private val MAPS = Regex("(?:найди|покажи)\\s+(?:на\\s+карт[еах]+|в\\s+(?:яндекс\\s+)?картах)\\s+(.+)$")
    private val MAPS_AFTER = Regex("(?:найди|покажи)\\s+(.+?)\\s+(?:на\\s+карт[еах]+|в\\s+(?:яндекс\\s+)?картах)$")
    private val WEB = Regex("(?:найди|поищи)\\s+в\\s+(?:браузере|интернете|сети|гугле|яндексе)\\s+(.+)$")
    private val MORE_ACTIONS = Regex("\\b(?:и|затем|потом|после этого)\\s+(?:включи|выключи|открой|запусти|нажми|напиши|отправь|поставь|удали|сохрани|скопируй|подпишись|выбери|прочитай|скажи|воспроизведи|позвони)\\b")
    private val PLAY_FIRST = Regex(
        """(?:^|\s)(?:(?:и|потом|затем)\s+)?(?:включи|воспроизведи|запусти|выбери|поставь)\s+перв(?:ое|ый|ую)\s+(?:видео|ролик)$""",
    )
    private val TOPIC = Regex("""(?:про|о|об)\s+(.+)$""")
    private val HOTSPOT_LOOKUP = Regex(
        "^(?:(?:в настройках )?(?:найди|найти|покажи|открой) (?:в настройках )?|" +
            "открой настройки и (?:найди|покажи|открой) )(?:мобильную )?точку доступа(?: wi[- ]?fi)?$",
    )
    private val SETTINGS_CHANGE = Regex("\\b(?:включить|выключить|выключи|включи|измени|переключи|настрой)\\b")
    private val SECTIONS = linkedMapOf(
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
        val text = goal.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        chain(text)?.let { return it }
        youtubePlay(text)?.let { return it }
        if (MORE_ACTIONS.containsMatchIn(text)) return null
        if (YOUTUBE.containsMatchIn(text)) {
            if (Regex("\\b(?:включи|воспроизведи|запусти)\\b").containsMatchIn(text)) return null
            val query = AFTER_SEARCH.find(text)?.groupValues?.get(1)?.replace(YOUTUBE_WORD, " ")?.trim()
            return query?.takeIf { it.isNotEmpty() && !YOUTUBE.matches(it) }?.let { AgentShortcut.YouTubeSearch(it) }
        }
        val place = MAPS.find(text) ?: MAPS_AFTER.find(text)
        if (place != null) return AgentShortcut.MapSearch(place.groupValues[1].trim())
        WEB.find(text)?.let { return AgentShortcut.WebSearch(it.groupValues[1].trim()) }
        if (HOTSPOT_LOOKUP.matches(text)) return AgentShortcut.Setting("hotspot")
        if (!SETTINGS.containsMatchIn(text) || SETTINGS_CHANGE.containsMatchIn(text)) return null
        return SECTIONS.entries.firstOrNull { it.key in text }?.let { AgentShortcut.Setting(it.value) }
    }

    /** «Найди видео про … и включи первое» — один запрос, без общего цикла по экрану. */
    private fun youtubePlay(text: String): AgentShortcut.YouTubeSearch? {
        if (!YOUTUBE.containsMatchIn(text) || !PLAY_FIRST.containsMatchIn(text)) return null
        val stripped = text.replace(PLAY_FIRST, " ").replace(Regex("\\s+"), " ").trim()
        val raw = AFTER_SEARCH.find(stripped)?.groupValues?.get(1)
            ?: TOPIC.find(stripped)?.groupValues?.get(1)
            ?: return null
        val query = raw.replace(YOUTUBE_WORD, " ")
            .replace(Regex("""^(?:на\s+)?(?:ютубе|ютьюбе|youtube)\s*"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (query.isEmpty() || YOUTUBE.matches(query)) return null
        return AgentShortcut.YouTubeSearch(query, playFirst = true)
    }

    private val DIAL = Regex(
        """^(?:открой\s+)?(?:телефон|звонилк\p{L}*|набор)\s+и\s+набери\s+(\d{2,15})\s*,?\s*(?:но\s+)?не\s+звони[.!]?$""",
    )
    private val TIMER = Regex(
        """^(?:открой\s+)?(?:часы|будильник)\s+и\s+поставь\s+таймер\s+на\s+(\d{1,2})\s+минут\p{L}*[.!]?$""",
    )
    private val SUM = Regex(
        """^(?:открой\s+)?калькулятор\s+и\s+посчитай\s+(\d{1,3})\s+плюс\s+(\d{1,3})[.!]?$""",
    )
    private val FIRST_RESULT = Regex(
        """^(?:открой\s+браузер\s+и\s+)?(?:найди|поищи)\s+(.+?)\s+и\s+открой\s+перв\p{L}*\s+результат$""",
    )
    private val OPEN_MAPS = Regex(
        """^открой\s+(?:яндекс\s+)?карты\s+и\s+(?:найди|покажи)\s+(.+)$""",
    )
    private val OPEN_BROWSER = Regex(
        """^открой\s+браузер\s+и\s+(?:найди|поищи)\s+(.+)$""",
    )
    private val EMERGENCY = Regex("""^(?:112|101|102|103|104|911)\b""")

    /** Цепочки из двух шагов, которые не нужно угадывать по экрану. */
    private fun chain(text: String): AgentShortcut? {
        DIAL.find(text)?.groupValues?.get(1)?.let { number ->
            if (!EMERGENCY.containsMatchIn(number)) return AgentShortcut.DialDraft(number)
        }
        TIMER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { minutes ->
            if (minutes in 1..10) return AgentShortcut.Timer(minutes * 60)
        }
        SUM.find(text)?.let { match ->
            val left = match.groupValues[1].toInt()
            val right = match.groupValues[2].toInt()
            return AgentShortcut.CalculatorSum(left, right)
        }
        FIRST_RESULT.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }?.let {
            return AgentShortcut.OpenFirstResult(it)
        }
        OPEN_MAPS.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return AgentShortcut.MapSearch(it)
        }
        OPEN_BROWSER.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }?.let {
            return AgentShortcut.WebSearch(it)
        }
        return null
    }
}
