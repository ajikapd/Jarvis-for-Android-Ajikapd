package com.jarvis.core.nlp

/**
 * Голос «Джарвис из фильма»: какие куски ответа звучат записью из саундпака.
 * Запись подставляется только в начало предложения и только когда смысл совпадает;
 * всё остальное ([Part.text]) читает обычный голос.
 */
object CinemaPhrases {
    sealed interface Part {
        data class Clip(val id: String) : Part
        data class Text(val text: String) : Part
    }

    private class Rule(val words: List<List<String>>, val clips: List<String>)

    private fun rule(clips: List<String>, vararg starts: String) =
        Rule(starts.map { it.split(' ') }, clips)

    /** Порядок важен: длинные начала раньше коротких. */
    private val RULES = listOf(
        rule(listOf("always_at_service"), "всегда к вашим услугам"),
        rule(listOf("at_service", "always_at_service"), "к вашим услугам"),
        rule(listOf("yes_sir", "yes_sir_2"), "слушаю вас", "слушаю", "да сэр"),
        rule(listOf("done"), "запрос выполнен"),
        rule(listOf("done", "aye"), "готово", "выполнено", "сделано"),
        rule(listOf("as_you_wish"), "как пожелаете", "хорошо сэр"),
        rule(listOf("good_morning"), "доброе утро"),
        rule(listOf("check_done"), "проверка завершена"),
        rule(listOf("loading"), "загружаю"),
        rule(listOf("no_more_info"), "другой информации нет", "больше информации нет"),
        rule(listOf("rebooted"), "я перезагрузился"),
        rule(listOf("connected"), "мы подключены и готовы"),
        rule(listOf("diagnostics"), "начинаю диагностику системы", "начинаю диагностику"),
        rule(listOf("power_off"), "отключаю питание"),
        rule(listOf("build"), "начинаю автоматическую сборку"),
        rule(listOf("subtle"), "очень тонкое замечание"),
        rule(listOf("congrats"), "поздравляю"),
        rule(listOf("what_for"), "чего вы пытаетесь добиться"),
        rule(listOf("working"), "мы работаем над проектом"),
    )

    /** Проценты, для которых в саундпаке есть запись. */
    private val BATTERY = setOf(2, 7, 11, 13, 15, 19, 48)
    private val BATTERY_RE = Regex("^(?:заряд|энергия)(?:\\s+батареи)?\\s+(\\d{1,3})\\s*%", RegexOption.IGNORE_CASE)

    private val SENTENCE_RE = Regex("(?<=[.!?…])\\s+")
    private val WORD_RE = Regex("[\\p{L}\\p{N}]+")

    private var turn = 0

    /** null — ни одной записи не подошло, текст целиком читает обычный голос. */
    fun split(text: String): List<Part>? {
        val parts = mutableListOf<Part>()
        var matched = false
        for (sentence in text.trim().split(SENTENCE_RE).filter { it.isNotBlank() }) {
            val hit = match(sentence.trim())
            if (hit == null) {
                appendText(parts, sentence.trim())
                continue
            }
            matched = true
            parts += Part.Clip(hit.first)
            val rest = hit.second.trimStart(' ', ',', '—', '-', ':').trim()
            if (rest.isNotEmpty() && WORD_RE.containsMatchIn(rest)) appendText(parts, rest.replaceFirstChar { it.uppercase() })
        }
        return if (matched) parts else null
    }

    private fun appendText(parts: MutableList<Part>, text: String) {
        val last = parts.lastOrNull()
        if (last is Part.Text) parts[parts.lastIndex] = Part.Text(last.text + " " + text) else parts += Part.Text(text)
    }

    /** Запись и остаток предложения после совпавшего начала. */
    private fun match(sentence: String): Pair<String, String>? {
        BATTERY_RE.find(sentence)?.let { m ->
            val pct = m.groupValues[1].toInt()
            // «48% и падает» не говорим, когда телефон заряжается.
            val charging = sentence.contains("зарядк", ignoreCase = true)
            if (pct in BATTERY && !(pct == 48 && charging)) {
                return "battery_$pct" to sentence.substring(m.range.last + 1)
            }
        }
        val words = WORD_RE.findAll(sentence).toList()
        val norm = words.map { it.value.lowercase().replace('ё', 'е') }
        for (rule in RULES) {
            for (start in rule.words) {
                if (norm.size < start.size || norm.subList(0, start.size) != start) continue
                var used = start.size
                // «…, сэр» уже звучит в записи.
                if (norm.getOrNull(used) == "сэр") used++
                val cut = if (used < words.size) words[used].range.first else sentence.length
                val clip = rule.clips[Math.floorMod(turn++, rule.clips.size)]
                return clip to sentence.substring(cut)
            }
        }
        return null
    }
}
