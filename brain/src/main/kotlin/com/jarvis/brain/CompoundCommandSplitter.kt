package com.jarvis.brain

/**
 * Делит составную голосовую команду на шаги:
 * «открой Telegram, найди маму и напиши я выезжаю».
 */
object CompoundCommandSplitter {

    private val stepVerbs = setOf(
        "открой", "откройте", "запусти", "закрой", "сверни",
        "нажми", "тапни", "кликни", "выбери", "найди", "поищи",
        "напиши", "напечатай", "введи", "отправь",
        "включи", "выключи", "поставь", "сделай",
        "пролистай", "прокрути", "свайпни", "свайп",
        "позвони", "набери", "покажи", "прочитай", "прочти", "опиши",
        "вернись", "назад", "домой", "подожди", "дождись", "жди",
    )

    private val hardSeparators = listOf(
        Regex("""\s*,\s*и\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+потом\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+затем\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+после\s+этого\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s*,\s+""", RegexOption.IGNORE_CASE),
    )

    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        var parts = listOf(trimmed)
        for (sep in hardSeparators) {
            parts = parts.flatMap { chunk ->
                chunk.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        val steps = mutableListOf<String>()
        for (part in parts) {
            steps += splitOnAndBeforeVerb(part)
        }
        return steps.map { it.trim() }.filter { it.isNotEmpty() }.let { list ->
            if (list.size <= 1) listOf(trimmed) else list
        }
    }

    /** «открой X и нажми Y» / «открой часы и что на экране» → шаги. */
    private fun splitOnAndBeforeVerb(text: String): List<String> {
        val tokens = text.split(Regex("""\s+"""))
        if (tokens.size < 4) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        var i = 1
        while (i < tokens.size - 1) {
            val tok = tokens[i].lowercase().trim(',', '.', '!', '?')
            if (tok == "и" && isStepStart(tokens, i + 1)) {
                val left = tokens.subList(start, i).joinToString(" ").trim()
                if (left.isNotEmpty()) out += left
                start = i + 1
            }
            i++
        }
        val rest = tokens.subList(start, tokens.size).joinToString(" ").trim()
        if (rest.isNotEmpty()) out += rest
        return if (out.size <= 1) listOf(text) else out
    }

    /** Старт нового шага после «и»: глагол или «что на экране». */
    private fun isStepStart(tokens: List<String>, idx: Int): Boolean {
        if (idx >= tokens.size) return false
        val w = tokens[idx].lowercase().trim(',', '.', '!', '?')
        if (w in stepVerbs) return true
        // «что на экране» / «что видно»
        if (w == "что" && idx + 1 < tokens.size) {
            val tail = tokens.subList(idx, minOf(idx + 4, tokens.size))
                .joinToString(" ")
                .lowercase()
            if (tail.contains("на экране") || tail.contains("видно") ||
                tail.contains("на экрану")
            ) {
                return true
            }
        }
        return false
    }

    fun isCompound(text: String): Boolean = split(text).size > 1
}
