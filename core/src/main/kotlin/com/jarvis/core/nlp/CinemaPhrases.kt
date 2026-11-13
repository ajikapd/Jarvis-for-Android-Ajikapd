package com.jarvis.core.nlp

/**
 * Голос «Джарвис из фильма»: как можно больше ответа звучит записями из саундпака,
 * обычный голос читает только то, чего в записях нет (числа, имена, факты).
 *
 * Два уровня:
 *  - [plan] знает, что сделал ассистент (инструмент и его итог), и заменяет ответ целиком:
 *    выполненное действие — «Запрос выполнен, сэр», открытие приложения — «Загружаю, сэр» и т. п.;
 *  - [split] разбирает любой текст по предложениям и узнаёт смысл по началу или ключевым словам:
 *    «готово», «пожалуйста», «не нашёл», «секунду», «не понял», «пока не умею»…
 */
object CinemaPhrases {
    sealed interface Part {
        data class Clip(val id: String) : Part
        data class Text(val text: String) : Part
    }

    /** Что известно об ответе. Без подсказки работает только разбор по тексту. */
    data class Hint(
        val toolId: String? = null,
        val toolOk: Boolean? = null,
        val needsConfirmation: Boolean = false,
        val needsClarification: Boolean = false,
    )

    /** Записи для слова активации: только из фильма. */
    val LISTENING = listOf("yes_sir", "at_service", "yes_sir_2", "always_at_service")
    private val DONE = listOf("done", "aye", "as_you_wish")

    /** Инструменты, которые что-то делают: их итог — подтверждение, а не сведения. */
    private val ACTIONS = setOf(
        "flashlight", "volume", "brightness", "music", "timer", "reminder", "notes", "calendar",
        "sms", "telegram.send", "phone.call", "phone.control", "system.setting", "screenshot",
        "camera", "clipboard", "navigate", "app.action", "agent.run", "announce.settings",
        "ui.click", "ui.global", "ui.swipe", "ui.type", "ui.wait",
    )

    /**
     * @param maxRest сколько слов после совпавшего начала запись может поглотить
     *   («Слушаю, сэр» — да, «Пожалуйста, уточните город» — уже нет)
     * @param keepRest если слов больше: true — запись и остаток обычным голосом,
     *   false — правило не подходит, предложение читается как есть
     * @param anywhere ключевые слова в любом месте короткого предложения
     */
    private class Rule(
        val starts: List<List<String>>,
        val clips: List<String>,
        val maxRest: Int,
        val keepRest: Boolean,
        val anywhere: Boolean = false,
    )

    private fun rule(clips: List<String>, maxRest: Int, keepRest: Boolean, vararg starts: String) =
        Rule(starts.map { it.split(' ') }, clips, maxRest, keepRest)

    private fun keywords(clips: List<String>, vararg words: String) =
        Rule(words.map { it.split(' ') }, clips, maxRest = 0, keepRest = false, anywhere = true)

    /** Порядок важен: длинные и точные начала раньше коротких. */
    private val RULES = listOf(
        rule(listOf("always_at_service"), 0, true, "всегда к вашим услугам"),
        rule(listOf("at_service", "always_at_service"), 0, true, "к вашим услугам"),
        rule(listOf("yes_sir", "yes_sir_2"), 1, false, "слушаю вас", "слушаю", "да сэр", "я здесь", "на связи"),
        // Благодарность и вежливость.
        rule(
            listOf("always_at_service", "at_service"), 1, false,
            "всегда пожалуйста", "пожалуйста", "не за что", "рад помочь", "рад был помочь", "обращайтесь", "всегда рад",
        ),
        // Приветствия: утро своё, остальное — «К вашим услугам».
        rule(listOf("good_morning"), 0, true, "доброе утро", "с добрым утром"),
        rule(listOf("at_service"), 0, true, "добрый день", "добрый вечер", "доброй ночи", "здравствуйте", "здравствуй", "привет"),
        // Подтверждения: остаток («не отправляю») читается, смысл не теряется.
        rule(listOf("done"), 0, true, "запрос выполнен"),
        rule(
            DONE, 0, true,
            "готово", "выполнено", "сделано", "будет сделано", "принято", "понял", "хорошо", "ладно",
            "конечно", "сейчас сделаю", "уже делаю", "как пожелаете", "слушаюсь",
        ),
        // Ожидание.
        rule(
            listOf("loading"), 2, true,
            "одну секунду", "один момент", "секунду", "минуту", "момент", "загружаю", "сейчас посмотрю",
            "ищу", "открываю", "подождите",
        ),
        // Нет сведений.
        rule(
            listOf("no_more_info"), 4, false,
            "другой информации нет", "больше информации нет", "информации нет", "ничего не нашёл",
            "ничего не нашел", "не нашёл", "не нашел", "нет данных", "не знаю",
        ),
        // Не понял.
        rule(listOf("what_for"), 3, false, "не понял", "не расслышал", "повторите", "уточните", "что вы имеете в виду"),
        // Пока не умеет.
        keywords(listOf("working"), "пока не умею", "не умею", "не поддерживается", "ещё не умею", "скоро научусь", "в разработке"),
        // Проверки и диагностика.
        rule(listOf("check_done"), 2, true, "проверка завершена", "проверил", "всё в порядке", "все системы в норме"),
        rule(listOf("diagnostics"), 0, true, "начинаю диагностику системы", "начинаю диагностику", "провожу диагностику", "запускаю диагностику"),
        // Состояние самого ассистента.
        rule(listOf("rebooted"), 2, false, "я перезагрузился", "перезагрузился", "перезапустился", "я снова в строю", "снова в строю"),
        rule(listOf("connected"), 2, false, "мы подключены и готовы", "подключено", "подключился", "готов к работе", "системы запущены"),
        rule(listOf("power_off"), 2, false, "отключаю питание", "отключаюсь", "выключаюсь", "до связи", "до свидания"),
        rule(listOf("build"), 3, true, "начинаю автоматическую сборку", "запускаю сценарий", "запускаю рутину", "выполняю сценарий"),
        // Беседа.
        rule(listOf("subtle"), 1, false, "очень тонкое замечание", "тонкое замечание", "остроумно", "справедливое замечание", "хорошее замечание"),
        rule(listOf("congrats"), 4, false, "поздравляю", "мои поздравления", "примите поздравления"),
        rule(listOf("what_for"), 1, false, "чего вы пытаетесь добиться", "зачем вам это"),
        rule(listOf("working"), 2, true, "мы работаем над проектом", "работаем над этим", "работаю над этим"),
    )

    /** Ключевое слово внутри предложения меняет его на запись, только если предложение короткое. */
    private const val ANYWHERE_MAX_WORDS = 8
    /** Общий переспрос без своих сведений. */
    private const val WHOLE_MAX_WORDS = 6

    /** Проценты, для которых в саундпаке есть запись. */
    private val BATTERY = setOf(2, 7, 11, 13, 15, 19, 48)
    private val BATTERY_RE = Regex(
        "^(?:сэр,?\\s*)?(?:заряд|энергия)(?:\\s+батареи)?(?:\\s+на\\s+исходе)?[:\\s]+(\\d{1,3})\\s*%",
        RegexOption.IGNORE_CASE,
    )

    private val SENTENCE_RE = Regex("(?<=[.!?…])\\s+")
    private val WORD_RE = Regex("[\\p{L}\\p{N}]+")
    private val DIGIT_RE = Regex("\\d")
    private val SIR_RE = Regex(",?\\s*\\bсэр\\b,?", RegexOption.IGNORE_CASE)

    private var turn = 0

    private fun pick(clips: List<String>) = clips[Math.floorMod(turn++, clips.size)]

    fun listening(): String = pick(LISTENING)

    /**
     * Весь ответ с учётом того, что сделал ассистент. null — записи не подошли,
     * ответ целиком читает обычный голос.
     */
    fun plan(text: String, hint: Hint?): List<Part>? {
        if (hint == null || hint.needsConfirmation) return split(text)
        if (hint.needsClarification) {
            // Общий переспрос — записью; конкретный вопрос («Кому отправить?») нужно прочитать.
            return if (words(text).size <= WHOLE_MAX_WORDS && matchRule(text)?.first == "what_for") {
                listOf(Part.Clip("what_for"))
            } else split(text)
        }
        val tool = hint.toolId
        if (tool != null && hint.toolOk == false) {
            return if (matchRule(firstSentence(text))?.first == "no_more_info") listOf(Part.Clip("no_more_info")) else split(text)
        }
        if (tool != null && hint.toolOk == true) {
            when {
                tool == "launcher" -> return listOf(Part.Clip("loading"))
                tool == "system.status" -> {
                    battery(text)?.let { (clip, rest) -> if (words(rest).isEmpty()) return listOf(Part.Clip(clip)) }
                    return listOf(Part.Clip("check_done")) + textParts(stripSir(text))
                }
                tool in ACTIONS -> return listOf(Part.Clip(pick(DONE))) + textParts(details(text))
            }
        }
        return split(text)
    }

    /** Разбор по предложениям. null — ни одной записи не подошло. */
    fun split(text: String): List<Part>? {
        val parts = mutableListOf<Part>()
        var matched = false
        for (raw in sentences(text)) {
            val sentence = raw.trim()
            val hit = battery(sentence) ?: matchSentence(sentence)
            if (hit == null) {
                appendText(parts, sentence)
                continue
            }
            matched = true
            if ((parts.lastOrNull() as? Part.Clip)?.id != hit.first) parts += Part.Clip(hit.first)
            val rest = hit.second.trimStart(' ', ',', '—', '-', ':', '.', '!', '?').trim()
            if (WORD_RE.containsMatchIn(rest)) appendText(parts, rest.replaceFirstChar { it.uppercase() })
        }
        return if (matched) parts else null
    }

    private fun sentences(text: String) = text.trim().split(SENTENCE_RE).filter { it.isNotBlank() }

    private fun firstSentence(text: String) = sentences(text).firstOrNull().orEmpty()

    private fun textParts(text: String): List<Part> {
        val t = text.trim()
        if (!WORD_RE.containsMatchIn(t)) return emptyList()
        return split(t) ?: listOf(Part.Text(t))
    }

    /** Из подтверждения действия остаются только предложения с числами: минуты, время, проценты. */
    private fun details(text: String): String =
        sentences(text).filter { DIGIT_RE.containsMatchIn(it) }.joinToString(" ") { stripSir(it) }

    private fun stripSir(text: String): String = text.replace(SIR_RE, "").replace(Regex("\\s+([.,!?])"), "$1").trim()

    private fun appendText(parts: MutableList<Part>, text: String) {
        val last = parts.lastOrNull()
        if (last is Part.Text) parts[parts.lastIndex] = Part.Text(last.text + " " + text) else parts += Part.Text(text)
    }

    private fun words(text: String) = WORD_RE.findAll(text).map { it.value.lowercase().replace('ё', 'е') }.toList()

    private fun battery(sentence: String): Pair<String, String>? {
        val m = BATTERY_RE.find(sentence) ?: return null
        val pct = m.groupValues[1].toInt()
        // «48% и падает» не говорим, когда телефон заряжается.
        val charging = sentence.contains("зарядк", ignoreCase = true)
        if (pct !in BATTERY || (pct == 48 && charging)) return null
        return "battery_$pct" to sentence.substring(m.range.last + 1)
    }

    /** Запись правила, совпавшего с началом текста (для решений в [plan]). */
    private fun matchRule(text: String): Pair<String, String>? = matchSentence(text.trim())

    /** Запись и остаток предложения, который ещё нужно прочитать. */
    private fun matchSentence(sentence: String): Pair<String, String>? {
        val found = WORD_RE.findAll(sentence).toList()
        val norm = found.map { it.value.lowercase().replace('ё', 'е') }
        // «Сэр, готово»: обращение в записи уже есть.
        val lead = if (norm.firstOrNull() == "сэр") 1 else 0
        for (rule in RULES) {
            for (start in rule.starts) {
                val key = start.map { it.replace('ё', 'е') }
                if (rule.anywhere) {
                    if (norm.size <= ANYWHERE_MAX_WORDS && indexOf(norm, key) >= 0) return pick(rule.clips) to ""
                    continue
                }
                if (!startsWith(norm, key, lead)) continue
                var used = lead + key.size
                if (norm.getOrNull(used) == "сэр") used++
                val rest = norm.size - used
                if (rest <= rule.maxRest) return pick(rule.clips) to ""
                if (!rule.keepRest) continue
                return pick(rule.clips) to sentence.substring(found[used].range.first)
            }
        }
        return null
    }

    private fun startsWith(words: List<String>, key: List<String>, from: Int): Boolean =
        words.size - from >= key.size && words.subList(from, from + key.size) == key

    private fun indexOf(words: List<String>, key: List<String>): Int {
        for (i in 0..words.size - key.size) if (words.subList(i, i + key.size) == key) return i
        return -1
    }
}
