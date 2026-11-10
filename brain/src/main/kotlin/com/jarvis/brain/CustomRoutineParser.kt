package com.jarvis.brain

import com.jarvis.core.routines.EventRule
import com.jarvis.core.routines.EventTrigger

/** Разбор «когда говорю поехали, включи блютуз и музыку». Шаги потом идут обычными командами. */
object CustomRoutineParser {
    sealed class Request {
        data class Define(val name: String, val steps: List<String>) : Request()
        data class DefineEvent(val rule: EventRule, val steps: List<String>) : Request()
        data class Delete(val name: String) : Request()
        object Names : Request()
        /** Синоним приложения: «называй телеграм телегой». */
        data class Alias(val alias: String, val app: String) : Request()
    }

    private val DEFINE = Regex(
        "^(?:когда|если)\\s+(?:я\\s+)?(?:говорю|скажу)\\s+(.+)$|^(?:создай|сделай|запомни)\\s+сценарий\\s+(.+)$|" +
            "^(?:создай|добавь|сделай)\\s+(?:новую\\s+)?команду\\s+(.+)$",
    )
    private val EVENT = Regex("^(?:когда|если|как только)\\s+(.+)$")
    private val LEAVING = Regex("отключ|сним|выним|вытащ|отсоедин|теря|пропад|убира")
    private val OPENING = Regex("(?:открываю|запускаю|захожу в|открою|запущу|зайду в)\\s+(.+)$")
    private val DELETE = Regex("^(?:удали|убери|забудь)\\s+(?:сценарий|команду)\\s+(.+)$")
    private val CALL_AS = Regex("^(?:называй|зови)\\s+(?:приложение\\s+)?(\\S+(?:\\s+\\S+)?)\\s+(\\S+)$")
    private val ADD_ALIAS = Regex("^(?:добавь\\s+)?синоним\\s+(.+?)\\s+(?:для|к)\\s+(?:приложения\\s+|приложению\\s+)?(.+)$")
    private val INSTRUMENTAL = listOf("ой", "ей", "ом", "ем", "ью")
    private val VERBS = setOf(
        "включи", "выключи", "открой", "запусти", "поставь", "сделай", "останови", "убавь", "прибавь", "отключи", "закрой",
        "скажи", "расскажи", "прочитай",
    )
    private val LINKS = setOf("то", "тогда", "это")

    fun parse(phrase: String): Request? {
        val text = phrase.lowercase().trim().trimEnd('.', '!', '?').replace(",", " ").replace(":", " ").replace(Regex("\\s+"), " ")
        if (text == "какие у меня сценарии" || text == "мои сценарии") return Request.Names
        DELETE.find(text)?.let { return Request.Delete(it.groupValues[1]) }
        ADD_ALIAS.find(text)?.let { return Request.Alias(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        CALL_AS.find(text)?.let { return Request.Alias(stem(it.groupValues[2]), it.groupValues[1].trim()) }
        val match = DEFINE.find(text) ?: return event(text)
        val tokens = match.groupValues.drop(1).first { it.isNotEmpty() }.split(' ')
        val verb = tokens.indexOfFirst { it in VERBS }
        if (verb <= 0) return null
        val name = tokens.take(verb).dropLastWhile { it in LINKS }.joinToString(" ")
        // «сделай сам …» целиком уходит агенту одним шагом.
        val rest = tokens.drop(verb)
        val steps = if (rest.size > 2 && rest[0] == "сделай" && rest[1] == "сам") {
            listOf("сделай сам: " + rest.drop(2).joinToString(" "))
        } else {
            steps(rest)
        }
        return if (name.isEmpty() || steps.isEmpty()) null else Request.Define(name, steps)
    }

    /** «телегой» → «телег»: синоним хранится основой, чтобы «открой телегу» тоже находилось. */
    private fun stem(word: String): String =
        INSTRUMENTAL.firstOrNull { word.length > it.length + 3 && word.endsWith(it) }?.let { word.dropLast(it.length) } ?: word

    /** «когда подключаю наушники, включи музыку»: условие до первого глагола команды. */
    private fun event(text: String): Request? {
        val tokens = EVENT.find(text)?.groupValues?.get(1)?.split(' ') ?: return null
        val verb = tokens.indexOfFirst { it in VERBS }
        if (verb <= 0) return null
        val rule = rule(tokens.take(verb).dropLastWhile { it in LINKS }.joinToString(" ")) ?: return null
        val steps = steps(tokens.drop(verb))
        return if (steps.isEmpty()) null else Request.DefineEvent(rule, steps)
    }

    private fun rule(condition: String): EventRule? {
        val off = LEAVING.containsMatchIn(condition)
        return when {
            "наушник" in condition || "гарнитур" in condition ->
                EventRule(if (off) EventTrigger.HEADPHONES_OFF else EventTrigger.HEADPHONES_ON)
            "заряд" in condition -> EventRule(if (off) EventTrigger.CHARGER_OFF else EventTrigger.CHARGER_ON)
            "вайфа" in condition || "вай-фа" in condition || "вай фа" in condition || "wi-fi" in condition || "wifi" in condition ->
                EventRule(if (off) EventTrigger.WIFI_OFF else EventTrigger.WIFI_ON)
            "разблокир" in condition -> EventRule(EventTrigger.UNLOCK)
            else -> OPENING.find(condition)?.let { EventRule(EventTrigger.APP_OPEN, it.groupValues[1]) }
        }
    }

    /** «включи блютуз и музыку»: глагол действует на обе части. */
    private fun steps(tokens: List<String>): List<String> {
        val result = ArrayList<String>()
        var current = ArrayList<String>()
        var verb = tokens.first()
        for (token in tokens) {
            when {
                token == "и" || token == "потом" || token == "затем" -> {
                    if (current.isNotEmpty()) result += current.joinToString(" ")
                    current = ArrayList()
                }
                current.isEmpty() && token !in VERBS -> current.addAll(listOf(verb, token))
                else -> {
                    if (current.isEmpty()) verb = token
                    current.add(token)
                }
            }
        }
        if (current.isNotEmpty()) result += current.joinToString(" ")
        return result
    }
}
