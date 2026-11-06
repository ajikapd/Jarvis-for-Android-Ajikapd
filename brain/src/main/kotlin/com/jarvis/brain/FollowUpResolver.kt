package com.jarvis.brain

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.reminder.ReminderParser
import java.util.UUID

/**
 * Повтор / патч последнего SkillCall для всего ассистента:
 * погода, знания, лаунчер («а теперь вк»), громкость, фонарик, таймер, музыка…
 */
object FollowUpResolver {
    /** Прямо названный таймер или будильник — новая команда, а не уточнение прошлой. */
    private val TIMER_NOUNS = listOf("таймер", "будильник", "секундомер")
    private const val HOUR_AND_HALF_MINUTES = 90
    private const val HALF_HOUR_MINUTES = 30

    fun resolve(text: String, last: ToolCall?): ToolCall? {
        if (last == null) return null
        val t = text.lowercase().trim().trimEnd('?', '!', '.', '…')
        if (t.isBlank()) return null

        if (isPlainReplay(t)) {
            return last.copy(requestId = newId())
        }

        volumeFollowUp(t, last)?.let { return it }
        brightnessFollowUp(t, last)?.let { return it }
        flashlightFollowUp(t, last)?.let { return it }
        musicFollowUp(t, last)?.let { return it }
        timerFollowUp(t, last)?.let { return it }
        launcherFollowUp(t, last)?.let { return it }
        reminderFollowUp(text, last)?.let { return it }

        // Knowledge follow-up можно без «а …» («почему их считают умными»)
        knowledgeFollowUp(t, last)?.let { return it }

        if (!looksLikeFollowUp(t) && !isCityOnlyFollowUp(t) && !isAppSwitchPhrase(t)) return null

        weatherFollowUp(t, last)?.let { return it }

        val but = BUT_PREFIX.find(t)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (but.isNotBlank()) {
            launcherFollowUp(but, last)?.let { return it }
            weatherFollowUp(but, last)?.let { return it }
            volumeFollowUp(but, last)?.let { return it }
            brightnessFollowUp(but, last)?.let { return it }
            timerFollowUp(but, last)?.let { return it }
            cityOnlyPatch(but, last)?.let { return it }
            knowledgeFollowUp(but, last)?.let { return it }
            // «а теперь вк» → but="вк" при last=launcher
            if (last.toolId == "launcher") {
                extractAppName(but)?.let { app ->
                    return last.copy(
                        requestId = newId(),
                        arguments = mapOf("action" to "open", "app" to app),
                    )
                }
            }
        }

        cityOnlyPatch(t, last)?.let { return it }
        launcherFollowUp(t, last)?.let { return it }

        return null
    }

    fun looksLikeFollowUp(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('?', '!', '.')
        if (isPlainReplay(t)) return true
        if (BUT_PREFIX.containsMatchIn(t)) return true
        if (t.startsWith("а ") || t.startsWith("а,") || t.startsWith("а теперь") || t.startsWith("теперь ")) return true
        if (t in setOf("завтра", "послезавтра", "сегодня")) return true
        if (isCityOnlyFollowUp(t)) return true
        if (isKnowledgeFollowUpPhrase(t)) return true
        if (isAppSwitchPhrase(t)) return true
        return false
    }

    private fun isCityOnlyFollowUp(t: String): Boolean =
        Regex("""^в(?:о)?\s+[а-яёa-z\-]{2,}""", RegexOption.IGNORE_CASE).containsMatchIn(t)

    private fun isWeatherCityPatch(t: String): Boolean {
        if (isCityOnlyFollowUp(t)) return true
        return Regex(
            """^а\s+в(?:о)?\s+[а-яёa-z\-]{2,}""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(t)
    }

    private fun isAppSwitchPhrase(t: String): Boolean {
        if (t.startsWith("а теперь") || t.startsWith("теперь ") || t.startsWith("а сейчас")) return true
        if (Regex("""^а\s+(открой|запусти)\s+\S+""").containsMatchIn(t)) return true
        return false
    }

    private fun isPlainReplay(t: String): Boolean =
        t in setOf(
            "ещё раз", "еще раз", "повтори", "повторы", "снова",
            "то же самое", "тоже самое", "то же", "повтори ещё раз", "повтори еще раз",
        )

    private fun isKnowledgeFollowUpPhrase(t: String): Boolean {
        // не путать с другими доменами
        if (t.contains("погод") || t.contains("фонарик") || t.contains("громк") ||
            t.contains("тише") || t.startsWith("открой") || t.startsWith("включи") ||
            t.startsWith("выключи") || t.startsWith("запусти")
        ) {
            return false
        }
        if (t in setOf("подробнее", "продолжи", "дальше", "ещё", "еще")) return true
        if (t.startsWith("почему") || t.startsWith("зачем") || t.startsWith("как ") ||
            t.startsWith("где ") || t.startsWith("когда ") ||
            t.startsWith("сколько ") || t.startsWith("чем ") || t.startsWith("кем ") ||
            t.startsWith("откуда ") || t.startsWith("куда ")
        ) {
            return true
        }
        // «какой/какая» только с местоимением / короткое уточнение
        if ((t.startsWith("какой ") || t.startsWith("какая ") || t.startsWith("какое ") || t.startsWith("какие ")) &&
            (t.contains(" он") || t.contains(" она") || t.contains(" они") || t.contains(" это") ||
                t.contains(" его") || t.contains(" её") || t.contains(" их") || t.length < 28)
        ) {
            return true
        }
        if (t.contains(" их ") || t.contains(" они ") || t.endsWith(" их") ||
            t.contains(" он ") || t.startsWith("он ") || t.endsWith(" он") ||
            t.contains(" она ") || t.startsWith("она ") ||
            t.contains(" его ") || t.contains(" её ") || t.contains(" ее ") ||
            t.startsWith("а почему") || t.startsWith("а зачем") || t.startsWith("а где")
        ) {
            return true
        }
        if (t == "это" || t.startsWith("а это") || t.startsWith("про это")) return true
        return false
    }

    private fun weatherFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "weather") return null
        // полный новый запрос («какая погода в Москве») — не патч
        if (t.contains("погод") || t.startsWith("какая ") || t.startsWith("какой ")) return null
        val day = when {
            t.contains("послезавтра") -> 2
            t.contains("завтра") -> 1
            t.contains("сегодня") -> 0
            else -> null
        }
        val city = extractCity(t)
        if (day == null && city == null) return null
        return last.copy(
            requestId = newId(),
            arguments = last.arguments.toMutableMap().apply {
                if (day != null) put("day", day)
                if (!city.isNullOrBlank()) put("city", city)
            },
        )
    }

    private fun cityOnlyPatch(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "weather") return null
        if (t.contains("погод") || t.startsWith("какая ") || t.startsWith("какой ")) return null
        val city = extractCity(t) ?: return null
        return last.copy(
            requestId = newId(),
            arguments = last.arguments + mapOf("city" to city),
        )
    }

    /**
     * «открой телеграм» → «а теперь вк» / «теперь инстаграм» / «а открой вк».
     * Не перехватывает обычные команды («сделай громче» и т.п.).
     */
    private fun launcherFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "launcher") return null
        val prevAction = last.arguments["action"]?.toString() ?: "open"
        if (prevAction == "force_stop") return null

        if (t in setOf("закрой", "сверни", "убери") ||
            t.startsWith("закрой ") || t.startsWith("сверни ")
        ) {
            val app = last.arguments["app"]?.toString().orEmpty()
            return last.copy(
                requestId = newId(),
                arguments = buildMap {
                    put("action", "minimize")
                    if (app.isNotBlank()) put("app", app)
                },
            )
        }

        val looksLikeSwitch = isAppSwitchPhrase(t) ||
            t.startsWith("открой ") || t.startsWith("запусти ") ||
            t.startsWith("а открой") || t.startsWith("а запусти") ||
            Regex("""^а\s+[a-zа-яё0-9.\-]{2,24}$""").matches(t)
        if (!looksLikeSwitch) return null

        val app = extractAppName(t) ?: return null
        if (appLooksLikeOtherCommand(app)) return null
        return last.copy(
            requestId = newId(),
            arguments = mapOf("action" to "open", "app" to app),
        )
    }

    private fun appLooksLikeOtherCommand(app: String): Boolean {
        val w = app.lowercase().split(Regex("\\s+"))
        val verbs = setOf(
            "сделай", "включи", "выключи", "поставь", "скажи", "какая", "какой",
            "громче", "тише", "ярче", "темнее", "погода", "таймер", "фонарик",
            "кто", "что", "почему", "зачем", "расскажи", "найди", "напиши",
        )
        return w.any { it in verbs }
    }

    private fun extractAppName(raw: String): String? {
        var s = raw.lowercase().trim()
            .removePrefix("а теперь ").removePrefix("а сейчас ").removePrefix("теперь ")
            .removePrefix("а давай ").removePrefix("давай ")
            .removePrefix("а ").trim()
        s = s.removePrefix("открой ").removePrefix("запусти ").removePrefix("открыть ")
            .removePrefix("приложение ").trim()
        s = s.trimEnd('?', '!', '.', ',', '…')
        if (s.isBlank() || s.length > 40) return null
        if (s in setOf(
                "ещё", "еще", "раз", "то", "же", "пожалуйста", "его", "её", "ее",
                "это", "туда", "сюда", "громче", "тише", "ярче",
            )
        ) {
            return null
        }
        // не путать с погодой / знаниями
        if (s.startsWith("в ") || s.startsWith("во ") || s.startsWith("почему") ||
            s.startsWith("что ") || s.startsWith("кто ")
        ) {
            return null
        }
        return s
    }

    /** «Когда напомнить?» — «завтра в девять»: ответ дополняет прошлую фразу. */
    private fun reminderFollowUp(text: String, last: ToolCall): ToolCall? {
        if (last.toolId != "reminder") return null
        val phrase = last.arguments["phrase"]?.toString() ?: return null
        val now = java.time.LocalDateTime.now()
        if (ReminderParser.parse(phrase, now).at != null) return null
        val answer = ReminderParser.parse(text, now)
        if (answer.at == null || answer.text.isNotBlank()) return null
        return last.copy(requestId = newId(), arguments = last.arguments + ("phrase" to "$phrase $text"))
    }

    private fun timerFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "timer" || last.arguments.containsKey("action")) return null
        if (TIMER_NOUNS.any { t.contains(it) }) return null
        val minutes = Regex("""(\d+)\s*(?:мин|минут)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: when {
                t.contains("полтора") -> HOUR_AND_HALF_MINUTES
                t.contains("полчаса") -> HALF_HOUR_MINUTES
                t.contains("час") && !t.contains("минут") -> 60
                else -> null
            }
            ?: return null
        return last.copy(
            requestId = newId(),
            arguments = last.arguments + mapOf("minutes" to minutes),
        )
    }

    private fun knowledgeFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "knowledge") return null
        if (isWeatherCityPatch(t) || isAppSwitchPhrase(t)) return null
        if (t.contains("погод") || t.startsWith("открой") || t.startsWith("включи") ||
            t.startsWith("выключи") || t.startsWith("запусти") || t.startsWith("сделай ")
        ) {
            return null
        }
        if (!looksLikeFollowUp(t) && !isKnowledgeFollowUpPhrase(t)) return null
        if (t.startsWith("кто такой") || t.startsWith("кто такая") || t.startsWith("кто такие") ||
            t.startsWith("что такое") || t.startsWith("расскажи про") || t.startsWith("расскажи о") ||
            t.startsWith("загугли")
        ) {
            return null
        }
        val prev = last.arguments["query"]?.toString()?.trim().orEmpty()
        val storedTopic = last.arguments["topic"]?.toString()?.trim().orEmpty()
        val exclude = last.arguments["exclude_title"]?.toString()?.trim().orEmpty()
            .ifBlank { last.arguments["title"]?.toString()?.trim().orEmpty() }
        if (prev.isBlank() && storedTopic.isBlank()) return null
        val topic = storedTopic.ifBlank { extractKnowledgeTopic(prev) }
        if (topic.isBlank()) return null
        val newQuery = rewriteKnowledgeFollowUp(t, topic)
        return last.copy(
            requestId = newId(),
            arguments = buildMap {
                put("query", newQuery.ifBlank { "$topic: $t" })
                put("topic", topic)
                if (exclude.isNotBlank()) put("exclude_title", exclude)
            },
        )
    }

    private fun extractKnowledgeTopic(prevQuery: String): String {
        var s = prevQuery.lowercase().trim()
        for (p in listOf(
            "кто такие ", "кто такой ", "кто такая ", "что такое ", "что это такое ",
            "расскажи про ", "расскажи о ", "загугли ",
        )) {
            if (s.startsWith(p)) {
                s = s.removePrefix(p).trim()
                break
            }
        }
        return s.split(Regex("\\s+")).take(3).joinToString(" ").trim().take(40)
    }

    private fun rewriteKnowledgeFollowUp(followUp: String, topic: String): String {
        var t = followUp.trim().removePrefix("а ").removePrefix("А ").trim()
        val topicWord = topic
        val genitive = when {
            topicWord.endsWith("ы") || topicWord.endsWith("и") ->
                topicWord.dropLast(1) + "ов"
            topicWord.endsWith("а") || topicWord.endsWith("я") ->
                topicWord.dropLast(1) + "ов"
            else -> topicWord
        }
        t = t.replace(Regex("""\bих\b""", RegexOption.IGNORE_CASE), genitive)
            .replace(Regex("""\bони\b""", RegexOption.IGNORE_CASE), topicWord)
            .replace(Regex("""\bим\b""", RegexOption.IGNORE_CASE), topicWord)
            .replace(Regex("""\bон\b""", RegexOption.IGNORE_CASE), topicWord)
            .replace(Regex("""\bона\b""", RegexOption.IGNORE_CASE), topicWord)
            .replace(Regex("""\bего\b""", RegexOption.IGNORE_CASE), genitive)
            .replace(Regex("""\bеё\b""", RegexOption.IGNORE_CASE), genitive)
            .replace(Regex("""\bее\b""", RegexOption.IGNORE_CASE), genitive)
            .replace(Regex("""\bэто\b""", RegexOption.IGNORE_CASE), topicWord)
        if (!t.contains(topicWord, ignoreCase = true) && !t.contains(genitive, ignoreCase = true)) {
            t = "$t ($topicWord)"
        }
        return t.trim()
    }

    private fun extractCity(t: String): String? {
        val m = Regex(
            """(?:^|\s)(?:а\s+)?в(?:о)?\s+([а-яёa-z\-]{2,})""",
            RegexOption.IGNORE_CASE,
        ).find(t)
        var city = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .trimEnd('?', '!', '.', ',', '…')
        if (city.isBlank() || city in setOf("том", "этом", "чём", "чем", "погоду", "погоде")) {
            return null
        }
        CITY_ALIASES[city.lowercase()]?.let { return it }
        val stemmed = when {
            city.endsWith("е") && city.length > 4 -> city.dropLast(1)
            city.endsWith("у") && city.length > 4 -> city.dropLast(1)
            city.endsWith("ы") && city.length > 4 -> city.dropLast(1) + "а"
            else -> city
        }
        CITY_ALIASES[stemmed.lowercase()]?.let { return it }
        return stemmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun volumeFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "volume") return null
        val direction = when {
            t.contains("громче") || t.contains("погромче") || t.contains("выше") -> "up"
            t.contains("тише") || t.contains("потише") || t.contains("ниже") -> "down"
            t.contains("макс") -> "max"
            t.contains("мут") || t.contains("без звука") -> "mute"
            else -> null
        } ?: return null
        return last.copy(
            requestId = newId(),
            arguments = last.arguments + mapOf("direction" to direction),
        )
    }

    private fun brightnessFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "brightness") return null
        val direction = when {
            t.contains("ярче") || t.contains("поярче") || t.contains("светлее") -> "up"
            t.contains("темнее") || t.contains("тусклее") -> "down"
            else -> null
        } ?: return null
        return last.copy(
            requestId = newId(),
            arguments = last.arguments + mapOf("direction" to direction),
        )
    }

    private fun flashlightFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "flashlight") return null
        val action = when {
            t.contains("выключ") || t.contains("погас") || t.contains("отключ") -> "off"
            t.contains("включ") || t.contains("зажг") -> "on"
            else -> null
        } ?: return null
        return last.copy(
            requestId = newId(),
            arguments = mapOf("action" to action),
        )
    }

    private fun musicFollowUp(t: String, last: ToolCall): ToolCall? {
        if (last.toolId != "music") return null
        val action = when {
            t.contains("пауз") || t.contains("стоп") -> "pause"
            t.contains("продолж") || t.contains("возобнов") -> "resume"
            t.contains("дальше") || t.contains("следующ") || t == "next" -> "next"
            t.contains("предыдущ") -> "previous"
            else -> null
        } ?: return null
        return last.copy(
            requestId = newId(),
            arguments = mapOf("action" to action),
        )
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private val BUT_PREFIX = Regex(
        """^(?:то\s*же\s*самое\s*[,:]?\s*)?(?:но|а\s+теперь|а\s+сейчас|теперь|только)\s+(.+)$""",
    )

    private val CITY_ALIASES = mapOf(
        "москве" to "Москва", "москвы" to "Москва", "москву" to "Москва", "москва" to "Москва",
        "казани" to "Казань", "казань" to "Казань",
        "питере" to "Санкт-Петербург", "питера" to "Санкт-Петербург", "питер" to "Санкт-Петербург",
        "петербурге" to "Санкт-Петербург", "петербург" to "Санкт-Петербург",
        "спб" to "Санкт-Петербург", "санкт-петербург" to "Санкт-Петербург",
        "сочи" to "Сочи",
        "донецке" to "Донецк", "донецка" to "Донецк", "донецк" to "Донецк",
        "новосибирске" to "Новосибирск", "новосибирск" to "Новосибирск",
        "екатеринбурге" to "Екатеринбург", "екатеринбург" to "Екатеринбург", "екб" to "Екатеринбург",
        "минске" to "Минск", "минск" to "Минск",
        "киеве" to "Киев", "киев" to "Киев",
    )
}
