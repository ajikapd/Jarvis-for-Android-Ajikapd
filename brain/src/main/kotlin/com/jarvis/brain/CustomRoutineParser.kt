package com.jarvis.brain

/** Разбор «когда говорю поехали, включи блютуз и музыку». Шаги потом идут обычными командами. */
object CustomRoutineParser {
    sealed class Request {
        data class Define(val name: String, val steps: List<String>) : Request()
        data class Delete(val name: String) : Request()
        object Names : Request()
    }

    private val DEFINE = Regex("^(?:когда|если)\\s+(?:я\\s+)?(?:говорю|скажу)\\s+(.+)$|^(?:создай|сделай|запомни)\\s+сценарий\\s+(.+)$")
    private val DELETE = Regex("^(?:удали|убери|забудь)\\s+сценарий\\s+(.+)$")
    private val VERBS = setOf(
        "включи", "выключи", "открой", "запусти", "поставь", "сделай", "останови", "убавь", "прибавь", "отключи", "закрой",
    )
    private val LINKS = setOf("то", "тогда", "это")

    fun parse(phrase: String): Request? {
        val text = phrase.lowercase().trim().trimEnd('.', '!', '?').replace(",", " ").replace(Regex("\\s+"), " ")
        if (text == "какие у меня сценарии" || text == "мои сценарии") return Request.Names
        DELETE.find(text)?.let { return Request.Delete(it.groupValues[1]) }
        val match = DEFINE.find(text) ?: return null
        val tokens = match.groupValues.drop(1).first { it.isNotEmpty() }.split(' ')
        val verb = tokens.indexOfFirst { it in VERBS }
        if (verb <= 0) return null
        val name = tokens.take(verb).dropLastWhile { it in LINKS }.joinToString(" ")
        val steps = steps(tokens.drop(verb))
        return if (name.isEmpty() || steps.isEmpty()) null else Request.Define(name, steps)
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
