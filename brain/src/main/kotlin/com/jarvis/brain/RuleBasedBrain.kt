package com.jarvis.brain

import com.jarvis.core.common.model.ConfidenceLevel
import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.Slot
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.nlp.RuNumbers
import com.jarvis.core.nlp.ContactPhraseNormalizer

/**
 * Детерминированный Brain (JARVIS_BRAIN_ARCHITECTURE §6, deterministic-first).
 * Быстрый слой маршрутизации: распознаёт команды по словам-триггерам и слотам
 * без обращения к LLM. Для чётких команд даёт SKILL_CALL с высоким confidence;
 * при неоднозначности — CLARIFY; при отсутствии совпадений — CHAT (передаём диалоговой модели).
 *
 * Покрывает скиллы: фонарик, громкость, яркость, будильник/таймер, сообщение, приложение.
 * Room для расширения: каждый скилл — маленький блок правил.
 */
class RuleBasedBrain(
    /** Облако понимает свободную речь: уточнять «это команда» вместо него незачем. */
    private val cloudAvailable: () -> Boolean = { false },
) : BrainRouter {

    override fun route(
        request: UserRequest,
        history: List<String>,
        topSkills: List<BrainRouter.SkillInfo>
    ): BrainResult {
        val words = normalize(request.text)
        if (words.isEmpty()) return BrainResult.Clarify(question = "Не расслышал. Повтори, пожалуйста.", confidence = 0.3f)

        // ── фонарик ────────────────────────────────────────────────
        flashLight(words, request)?.let { return it }
        // ── история звонков / SMS / календарь (до «позвони») ────────
        callHistory(words, request)?.let { return it }
        smsRead(words, request)?.let { return it }
        calendarList(words, request)?.let { return it }
        // ── громкость ──────────────────────────────────────────────
        volume(request.text, words, request)?.let { return it }
        // ── яркость экрана ─────────────────────────────────────────
        brightness(words, request)?.let { return it }
        // ── будильник / таймер ─────────────────────────────────────
        timer(words, request)?.let { return it }
        // ── Telegram: «напиши … в телеграмме» (до SMS) ─────────────
        sendTelegram(words, request)?.let { return it }
        // ── сообщение SMS ──────────────────────────────────────────
        sendSms(words, request)?.let { return it }
        screenshot(words, request)?.let { return it }
        // ── погода / музыка / знания (до launcher — «включи песню») ─
        weather(words, request)?.let { return it }
        music(words, request)?.let { return it }
        knowledge(words, request)?.let { return it }
        // ── системные настройки (до launcher — «открой wifi») ──────
        systemSetting(words, request)?.let { return it }
        // ── запуск приложения ──────────────────────────────────────
        launchApp(words, request)?.let { return it }
        // ── UI раньше контактов: «найди маму» на экране vs «найди контакт» ──
        uiActions(words, request)?.let { return it }
        clipboard(words, request)?.let { return it }
        notifications(words, request)?.let { return it }
        // ── событие в календаре ────────────────────────────────────
        createEvent(words, request)?.let { return it }
        // ── звонок ─────────────────────────────────────────────────
        callPhone(words, request)?.let { return it }
        // ── контакты ───────────────────────────────────────────────
        contacts(words, request)?.let { return it }

        // ничего не совпало — обычный разговор или неопределённая команда
        if (looksLikeCommand(words) && !cloudAvailable()) {
            return BrainResult.Clarify(question = "Понял, что это команда, но уточни, пожалуйста, что сделать.", confidence = 0.4f)
        }
        return BrainResult.Chat(confidence = 0.9f)
    }

    // ── правила скиллов ───────────────────────────────────────────

    private fun callHistory(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        val hit = text.contains("история звон") || text.contains("историю звон") ||
            text.contains("последние звон") || text.contains("кто звонил") ||
            text.contains("кто мне звонил") ||
            text.contains("пропущенные") ||
            (words.any { it.startsWith("истор") } && words.any { it.startsWith("звон") })
        if (!hit) return null
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.CALL,
                normalizedText = text,
                slots = mapOf("action" to Slot("action", "history")),
                confidence = 0.9f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.9f,
        )
    }

    private fun smsRead(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        val hit = text.contains("прочитай смс") || text.contains("прочитай sms") ||
            text.contains("последние смс") || text.contains("входящие смс") ||
            text.contains("какие смс") || text.contains("что в смс") ||
            (words.any { it in setOf("прочитай", "покажи", "какие") } &&
                words.any { it in setOf("смс", "sms", "сообщения") })
        if (!hit) return null
        // Не путать с «напиши/отправь смс» (само слово «смс» в SMS_VERBS — не блокер для чтения)
        if (words.any { it in setOf("напиши", "отправь", "передай", "напечатай", "отправить") }) return null
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.SEND_SMS,
                normalizedText = text,
                slots = mapOf("action" to Slot("action", "read")),
                confidence = 0.9f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.9f,
        )
    }

    private fun calendarList(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        val hit = text.contains("что в календаре") || text.contains("какие события") ||
            text.contains("ближайшие события") || text.contains("расписание") ||
            (words.any { it in setOf("покажи", "какие", "что") } &&
                words.any { it.startsWith("календар") || it.startsWith("событ") || it == "встречи" })
        if (!hit) return null
        if (words.any { it in EVENT_VERBS }) return null
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.CREATE_EVENT,
                normalizedText = text,
                slots = mapOf("action" to Slot("action", "list")),
                confidence = 0.9f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.9f,
        )
    }

    private fun flashLight(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        val freeOn = text == "посвети" || text == "подсвети" || text.contains("сделай светло")
        val noun = words.any { it in LIGHT_NOUNS } || freeOn
        if (!noun) return null
        val on = words.any { it in ON_VERBS } || freeOn
        val off = words.any { it in OFF_VERBS } || words.contains("убери")

        val action = when {
            on && !off -> "on"
            off && !on -> "off"
            else -> return null // неоднозначно или только "фонарик"
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.FLASHLIGHT,
                normalizedText = words.joinToString(" "),
                slots = mapOf("action" to Slot("action", action)),
                confidence = 0.95f,
                confidenceLevel = ConfidenceLevel.HIGH
            ),
            confidence = 0.95f
        )
    }

    private fun volume(rawText: String, words: List<String>, request: UserRequest): BrainResult? {
        val text = rawText.lowercase()
        val dirUp = words.any { it in VOLUME_UP } || text.contains("громче") || text.contains("погромче")
        val dirDown = words.any { it in VOLUME_DOWN } || text.contains("тише") || text.contains("потише") ||
            text.contains("не так громко") || text.contains("слишком громко")
        val mute = text.contains("выключи звук") || text.contains("без звука") || text.contains("заглуши")
        val unmute = text.contains("включи звук") || text.contains("верни звук")
        val max = words.any { it in VOLUME_MAX } || text.contains("на максимум") || text.contains("максимальную")

        val direction = when {
            mute && !unmute -> "mute"
            unmute && !mute -> "unmute"
            max -> "max"
            dirUp && !dirDown -> "up"
            dirDown && !dirUp -> "down"
            else -> return null
        }

        val percent = percentOf(words)
        val target = percent != null && !mute && !unmute && !max &&
            words.any { it.startsWith("громкост") || it.startsWith("звук") }
        val slots = mutableMapOf(
            "direction" to Slot("direction", if (target) "set" else direction),
        )
        if (percent != null && (target || direction == "down")) {
            slots["percent"] = Slot("percent", percent)
        }

        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.VOLUME,
                normalizedText = words.joinToString(" "),
                slots = slots,
                confidence = 0.92f,
                confidenceLevel = ConfidenceLevel.HIGH
            ),
            confidence = 0.92f
        )
    }

    private fun brightness(words: List<String>, request: UserRequest): BrainResult? {
        val isBright = words.any { it in BRIGHTNESS_NOUNS }
        if (!isBright) return null
        val up = words.any { it in BRIGHT_UP }
        val down = words.any { it in BRIGHT_DOWN }
        val percent = percentOf(words)

        val direction = when {
            percent != null -> "set"
            up && !down -> "up"
            down && !up -> "down"
            else -> return null
        }

        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.BRIGHTNESS,
                normalizedText = words.joinToString(" "),
                slots = buildMap {
                    put("direction", Slot("direction", direction))
                    if (percent != null) put("percent", Slot("percent", percent))
                },
                confidence = 0.9f,
                confidenceLevel = ConfidenceLevel.HIGH
            ),
            confidence = 0.9f
        )
    }

    private fun timer(words: List<String>, request: UserRequest): BrainResult? {
        val isTimer = words.any { it in TIMER_NOUNS } || words.any { it in REMIND_VERBS } ||
            (words.contains("засеки") && words.any { it in MINUTE_WORDS || it in SECOND_WORDS || it in HOUR_WORDS })
        if (!isTimer) return null
        val text = words.joinToString(" ")
        if (words.contains("будильник")) return alarm(words)
        if (words.any { it in TIMER_CANCEL_VERBS }) {
            return skill(IntentType.TIMER, mapOf("action" to "cancel"), text, 0.94f)
        }
        if (text.contains("сколько") || text.contains("осталось")) {
            return skill(IntentType.TIMER, mapOf("action" to "status"), text, 0.94f)
        }
        val minutes = extractMinutes(words)
        val seconds = extractSeconds(words)
        if (minutes == null && seconds == null) {
            return BrainResult.Clarify(
                question = "На сколько установить таймер?",
                confidence = 0.5f
            )
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.TIMER,
                normalizedText = words.joinToString(" "),
                slots = mapOf(
                    "minutes" to Slot("minutes", minutes ?: 0),
                    "seconds" to Slot("seconds", seconds ?: 0)
                ),
                confidence = 0.94f,
                confidenceLevel = ConfidenceLevel.HIGH
            ),
            confidence = 0.94f
        )
    }

    /** «будильник на семь утра», «на 7 30», «на восемь вечера», «на семь тридцать». */
    private fun alarm(words: List<String>): BrainResult {
        val na = words.indexOfFirst { it == "на" || it == "в" }
        val numberEnds = (na + 1 until words.size).filter { i ->
            RuNumbers.endingAt(words, i) != null && RuNumbers.endingAt(words, i + 1) == null
        }
        val first = (na + 1 until words.size).firstOrNull { RuNumbers.endingAt(words, it) != null }
            ?: return BrainResult.Clarify(question = "На какое время поставить будильник?", confidence = 0.5f)
        val hourRaw = RuNumbers.endingAt(words, first) ?: 0
        val minute = numberEnds.lastOrNull()?.takeIf { it > first }?.let { RuNumbers.endingAt(words, it) } ?: 0
        val pm = words.any { it in ALARM_PM_WORDS }
        val hour = if (pm && hourRaw in 1..11) hourRaw + 12 else hourRaw
        return skill(
            IntentType.TIMER,
            mapOf("action" to "alarm", "hour" to hour, "minute" to minute),
            words.joinToString(" "),
            0.94f,
        )
    }

    private fun percentOf(words: List<String>): Int? {
        words.firstOrNull { it.endsWith("%") }?.trimEnd('%')?.toIntOrNull()?.let { return it }
        return RuNumbers.before(words, PERCENT_WORDS)
    }

    private fun sendTelegram(words: List<String>, request: UserRequest): BrainResult? {
        val tgIdx = words.indexOfFirst { isTelegramToken(it) }
        if (tgIdx < 0) return null
        val verbIdx = words.indexOfFirst { it in TG_WRITE_VERBS }
        if (verbIdx < 0 || verbIdx > tgIdx) return null

        val skip = setOf("в", "во", "на", "чат", "чате", "сообщение", "смс", "sms", "пожалуйста")
        // «напиши любимой жене в телеграмме [текст]» — всё до «в telegram» = контакт
        val beforeTg = words.subList(verbIdx + 1, tgIdx).filterNot { it in skip }
        val afterTg = if (tgIdx + 1 < words.size) {
            words.subList(tgIdx + 1, words.size).filterNot { it in skip }
        } else {
            emptyList()
        }

        val chatRaw = beforeTg.joinToString(" ").trim()
        val message = afterTg.joinToString(" ").trim()
        val chat = if (chatRaw.isBlank()) null else normalizeChatName(chatRaw)

        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.SEND_TELEGRAM,
                normalizedText = words.joinToString(" "),
                slots = buildMap {
                    if (!chat.isNullOrBlank()) put("chat", Slot("chat", chat))
                    if (message.isNotBlank()) put("text", Slot("text", message))
                },
                confidence = 0.94f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.94f,
        )
    }

    private fun isTelegramToken(w: String): Boolean =
        w == "telegram" || w == "тг" || w.startsWith("телеграм")

    private fun normalizeChatName(raw: String): String {
        val n = raw.lowercase().trim()
        return when {
            n.startsWith("избран") -> "Избранное"
            n == "saved" || n.contains("saved messages") -> "Избранное"
            else -> ContactPhraseNormalizer.canonical(raw)
        }
    }

    private fun sendSms(words: List<String>, request: UserRequest): BrainResult? {
        val hasVerb = words.any { it in SMS_VERBS }
        if (!hasVerb) return null
        // Составная UI-команда («открой … и напиши») — не SMS
        if (words.any {
            it in setOf("открой", "откройте", "запусти", "нажми", "найди", "подожди", "telegram", "тг") ||
                it.startsWith("телеграм")
        }) {
            return null
        }
        val after = words.dropWhile { it !in SMS_VERBS }.drop(1)
        // «напиши в поиск …» — это UI-ввод, не SMS
        if (after.firstOrNull() in setOf("в", "сюда", "тут", "там")) return null
        if (after.isEmpty()) {
            return BrainResult.Clarify(question = "Что написать в сообщении?", confidence = 0.5f)
        }

        val msgIdx = after.indexOfFirst { it in setOf("сообщение", "смс", "sms") }
        val recipientStop = setOf(
            "что", "текст", "сообщение", "смс", "sms", "мне", "сюда", "туда", "пожалуйста",
            "на", "в", "поле", "экране",
        )
        val recipient: String?
        val text: String
        if (msgIdx >= 0) {
            recipient = after.take(msgIdx).joinToString(" ").ifBlank { null }
            text = after.drop(msgIdx + 1).joinToString(" ").trim()
        } else if (after.size >= 2 && after.first() !in recipientStop) {
            recipient = after.first()
            text = after.drop(1).joinToString(" ").trim()
        } else {
            recipient = null
            text = after.joinToString(" ").trim()
        }
        if (text.isBlank()) {
            return BrainResult.Clarify(question = "Что написать в сообщении?", confidence = 0.5f)
        }

        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.SEND_SMS,
                normalizedText = words.joinToString(" "),
                slots = buildMap {
                    put("text", Slot("text", text))
                    if (!recipient.isNullOrBlank()) put("recipient", Slot("recipient", recipient))
                },
                confidence = 0.92f,
                confidenceLevel = ConfidenceLevel.HIGH
            ),
            confidence = 0.92f
        )
    }

    private fun createEvent(words: List<String>, request: UserRequest): BrainResult? {
        val hasVerb = words.any { it in EVENT_VERBS }
        if (!hasVerb) return null
        // «напомни через 5 минут» — это таймер, а не событие
        val hasDuration = words.any { it in MINUTE_WORDS } || words.any { it in SECOND_WORDS } || words.any { it in HOUR_WORDS }
        if (hasDuration) return null
        // Отбрасываем служебные слова: глагол + объектное существительное («событие», «встреча»)
        var titleWords = words.dropWhile { it !in EVENT_VERBS }.drop(1)
        titleWords = titleWords.dropWhile { it in EVENT_NOUNS }
        if (titleWords.isEmpty()) {
            return BrainResult.Clarify(question = "Какое событие добавить в календарь?", confidence = 0.5f)
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.CREATE_EVENT,
                normalizedText = words.joinToString(" "),
                slots = mapOf("title" to Slot("title", titleWords.joinToString(" "))),
                confidence = 0.7f,
                confidenceLevel = ConfidenceLevel.MEDIUM
            ),
            confidence = 0.7f
        )
    }

    private fun launchApp(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        // «принудительно закрой / убей / force stop»
        val force = text.contains("принудительн") || text.contains("force") ||
            words.any { it in setOf("убей", "прибей", "выгрузи") } ||
            (words.contains("останови") && words.any { it.startsWith("приложен") || it.startsWith("процесс") })
        if (force || (words.any { it in setOf("закрой", "сверни") } && text.contains("насовсем"))) {
            val skip = setOf(
                "закрой", "сверни", "убей", "прибей", "выгрузи", "останови",
                "force", "stop", "приложение", "апп", "процесс", "насовсем",
            )
            val appName = words
                .filterNot { it.startsWith("принудит") || it in skip }
                .joinToString(" ")
                .trim()
            return BrainResult.SkillCall(
                intent = Intent(
                    type = IntentType.LAUNCH_APP,
                    normalizedText = text,
                    slots = buildMap {
                        put("action", Slot("action", "force_stop"))
                        if (appName.isNotBlank()) put("app", Slot("app", appName))
                    },
                    confidence = 0.9f,
                    confidenceLevel = ConfidenceLevel.HIGH,
                ),
                confidence = 0.9f,
            )
        }
        if (words.any { it in setOf("закрой", "сверни") }) {
            val appName = words.dropWhile { it !in setOf("закрой", "сверни") }.drop(1)
                .filterNot { it in setOf("приложение", "апп") }
                .joinToString(" ").trim()
            return BrainResult.SkillCall(
                intent = Intent(
                    type = IntentType.LAUNCH_APP,
                    normalizedText = text,
                    slots = buildMap {
                        put("action", Slot("action", "minimize"))
                        if (appName.isNotBlank()) put("app", Slot("app", appName))
                    },
                    confidence = 0.88f,
                    confidenceLevel = ConfidenceLevel.HIGH,
                ),
                confidence = 0.88f,
            )
        }
        val hasVerb = words.any { it in LAUNCH_VERBS }
        if (!hasVerb) return null
        // Наивно: берём имя приложения из последних слов
        val appName = words.dropWhile { it !in LAUNCH_VERBS }.drop(1).joinToString(" ").trim()
        if (appName.isBlank()) {
            return BrainResult.Clarify(question = "Какое приложение открыть?", confidence = 0.5f)
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.LAUNCH_APP,
                normalizedText = words.joinToString(" "),
                slots = mapOf(
                    "action" to Slot("action", "open"),
                    "app" to Slot("app", appName),
                ),
                confidence = 0.88f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.88f,
        )
    }

    private fun callPhone(words: List<String>, request: UserRequest): BrainResult? {
        if (words.none { it in CALL_VERBS }) return null
        val contact = words.dropWhile { it !in CALL_VERBS }.drop(1).joinToString(" ").trim()
        if (contact.isBlank()) {
            return BrainResult.Clarify(question = "Кому позвонить?", confidence = 0.5f)
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.CALL,
                normalizedText = words.joinToString(" "),
                slots = mapOf("contact" to Slot("contact", contact)),
                confidence = 0.9f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.9f,
        )
    }

    private fun contacts(words: List<String>, request: UserRequest): BrainResult? {
        val has = words.any { it in CONTACT_WORDS } ||
            (words.any { it in setOf("найди", "какой") } && words.any { it.contains("номер") || it == "контакт" })
        if (!has) return null
        val query = words.filterNot { it in CONTACT_WORDS || it in setOf("найди", "какой", "номер", "у") }
            .joinToString(" ").trim()
        if (query.isBlank()) {
            return BrainResult.Clarify(question = "Какой контакт найти?", confidence = 0.5f)
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.CONTACTS,
                normalizedText = words.joinToString(" "),
                slots = mapOf("query" to Slot("query", query)),
                confidence = 0.85f,
                confidenceLevel = ConfidenceLevel.MEDIUM,
            ),
            confidence = 0.85f,
        )
    }

    private fun systemSetting(words: List<String>, request: UserRequest): BrainResult? {
        // Не перехватывать «введи wifi» / «нажми bluetooth» — это UI.
        if (words.any { it in setOf("введи", "напиши", "напечатай", "нажми", "тапни", "кликни", "найди") }) {
            return null
        }
        val text = words.joinToString(" ")
        if (text.contains("настройк")) {
            SETTINGS_SECTIONS.entries.firstOrNull { (key, _) -> text.contains(key) }?.let { (_, section) ->
                return skill(IntentType.SYSTEM_SETTING, mapOf("name" to section, "value" to "open"), text, 0.9f)
            }
        }
        val name = when {
            words.any { it in WIFI_WORDS } || text.contains("вай") -> "wifi"
            words.any { it in BT_WORDS } || text.contains("bluetooth") || text.contains("блютус") -> "bluetooth"
            words.any { it in AIRPLANE_WORDS } || text.contains("самол") || text.contains("авиа") -> "airplane"
            else -> return null
        }
        val value = when {
            words.any { it in ON_VERBS } -> "on"
            words.any { it in OFF_VERBS } -> "off"
            words.any { it in setOf("открой", "откройте", "покажи") } -> "open"
            else -> "open"
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.SYSTEM_SETTING,
                normalizedText = text,
                slots = mapOf(
                    "name" to Slot("name", name),
                    "value" to Slot("value", value),
                ),
                confidence = 0.9f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.9f,
        )
    }

    private fun uiActions(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        when {
            text.contains("что на экране") || text.contains("прочитай экран") ||
                text.contains("прочти экран") || text.contains("опиши экран") ||
                (words.contains("экран") && words.any { it in setOf("что", "какие", "прочитай", "прочти", "опиши") }) ->
                return skill(IntentType.UI_READ, emptyMap(), text, 0.92f)

            words.any { it in setOf("подожди", "дождись", "жди") } || text.contains("пока появится") -> {
                val waitVerbs = setOf("подожди", "дождись", "жди", "пока")
                val query = words.dropWhile { it !in waitVerbs }.drop(1)
                    .filterNot { it in setOf("пока", "появится", "кнопку", "элемент", "на", "экране") }
                    .joinToString(" ").trim()
                return if (query.isBlank()) {
                    BrainResult.Clarify(question = "Чего ждать на экране?", confidence = 0.5f)
                } else {
                    skill(IntentType.UI_WAIT, mapOf("query" to query, "timeoutMs" to 5000), text, 0.88f)
                }
            }

            words.any { it in setOf("назад", "вернись") } ->
                return skill(IntentType.UI_GLOBAL, mapOf("action" to "back"), text, 0.9f)
            words.any { it in setOf("домой", "наглавную") } || text.contains("домашний экран") ->
                return skill(IntentType.UI_GLOBAL, mapOf("action" to "home"), text, 0.9f)
            words.any { it in setOf("недавние", "недавних") } ->
                return skill(IntentType.UI_GLOBAL, mapOf("action" to "recents"), text, 0.85f)
            text.contains("быстр") && text.contains("настрой") ->
                return skill(IntentType.UI_GLOBAL, mapOf("action" to "quick_settings"), text, 0.9f)
            text.contains("шторк") || (words.contains("открой") && words.contains("уведомления")) ->
                return skill(IntentType.UI_GLOBAL, mapOf("action" to "notifications"), text, 0.85f)
            words.any { it in setOf("заблокируй", "блокировка") } && words.any { it in setOf("экран", "телефон") } ->
                return skill(IntentType.UI_GLOBAL, mapOf("action" to "lock"), text, 0.88f)

            words.any { it in setOf("пролистай", "прокрути", "свайпни", "свайп") } -> {
                val untilIdx = words.indexOfFirst { it in setOf("до", "пока") }
                val until = if (untilIdx >= 0) {
                    words.drop(untilIdx + 1).joinToString(" ").trim()
                } else ""
                val dir = when {
                    words.any { it in setOf("вниз", "ниже") } -> "down"
                    words.any { it in setOf("вверх", "выше") } -> "up"
                    words.any { it in setOf("влево") } -> "left"
                    words.any { it in setOf("вправо") } -> "right"
                    else -> "up"
                }
                return if (until.isNotBlank()) {
                    skill(IntentType.UI_SWIPE, mapOf("direction" to dir, "until" to until), text, 0.9f)
                } else {
                    skill(IntentType.UI_SWIPE, mapOf("direction" to dir), text, 0.85f)
                }
            }

            words.any { it in setOf("нажми", "тапни", "кликни", "выбери", "найди", "поищи") } ||
                text.contains("долго нажми") || text.contains("удерживай") -> {
                // «найди контакт/номер» — не клик по экрану
                if (words.any { it in setOf("нажми", "тапни", "кликни", "выбери", "найди", "поищи") } &&
                    (words.any { it.contains("контакт") } || words.any { it.contains("номер") })
                ) {
                    return null
                }
                val longPress = text.contains("долго") || text.contains("удерживай") ||
                    words.any { it in setOf("долгое", "долгий") }
                val clickVerbs = setOf("нажми", "тапни", "кликни", "выбери", "найди", "поищи", "удерживай")
                var query = words.dropWhile { it !in clickVerbs }.drop(1)
                    .filterNot { it in setOf("на", "по", "кнопку", "элемент", "чат", "с", "долго", "долгое") }
                    .joinToString(" ").trim()
                if (query.startsWith("чат ")) query = query.removePrefix("чат ").trim()
                if (query.isBlank()) {
                    return BrainResult.Clarify(question = "На что нажать?", confidence = 0.5f)
                }
                return skill(
                    IntentType.UI_CLICK,
                    buildMap {
                        put("query", query)
                        if (longPress) put("longPress", true)
                        put("scroll", true)
                    },
                    text,
                    0.9f,
                )
            }

            words.any { it in setOf("напиши", "напечатай", "введи") } &&
                words.none { it in SMS_VERBS - setOf("напиши", "напечатай") } -> {
                // SMS уже обработан выше; сюда попадаем для UI-ввода («введи …»).
                val typed = words.dropWhile { it !in setOf("напиши", "напечатай", "введи") }
                    .drop(1)
                    .filterIndexed { i, w -> !(i == 0 && w in setOf("на", "в")) }
                    .joinToString(" ")
                    .removePrefix("экране ")
                    .removePrefix("поле ")
                    .trim()
                if (typed.isBlank()) return BrainResult.Clarify(question = "Что ввести?", confidence = 0.5f)
                // Если это выглядит как SMS с получателем («напиши маме привет») — не перехватываем:
                // sendSms уже отработал раньше. Сюда доходим только без SMS-матча.
                return skill(IntentType.UI_TYPE, mapOf("text" to typed), text, 0.85f)
            }
        }
        return null
    }

    private fun weather(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        val hit = words.any { it in WEATHER_WORDS } ||
            text.contains("градус") || text.contains("на улице")
        if (!hit) return null
        // «будет ли дождь в Москве» / «погода в казани»
        val city = extractCityAfter(words, setOf("в", "во"))
            ?: extractCityAfter(words, setOf("погода"))
        val day = when {
            text.contains("послезавтра") -> 2
            text.contains("завтра") -> 1
            text.contains("сегодня") -> 0
            else -> null
        }
        return BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.WEATHER,
                normalizedText = text,
                slots = buildMap {
                    if (!city.isNullOrBlank()) put("city", Slot("city", city))
                    if (day != null) put("day", Slot("day", day))
                },
                confidence = 0.92f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.92f,
        )
    }

    private fun music(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        when {
            words.any { it in setOf("пауза", "стоп") } ||
                (words.any { it in OFF_VERBS } && words.any { it in MUSIC_NOUNS }) ||
                text.contains("на паузу") || text.contains("поставь на паузу") ->
                return skill(IntentType.MUSIC, mapOf("action" to "pause"), text, 0.92f)

            // продолжить / воспроизведение / играй дальше
            words.any { it in RESUME_VERBS } ||
                text.contains("продолж") ||
                text.contains("возобнов") ||
                (text.contains("воспроизведен") && words.any { it in RESUME_VERBS || it.startsWith("продолж") }) ||
                text == "play" || text.contains("играй дальше") || text.contains("давай играй") ->
                return skill(IntentType.MUSIC, mapOf("action" to "resume"), text, 0.92f)

            words.any { it in setOf("дальше", "следующий", "следующая", "next") } &&
                (words.any { it in MUSIC_NOUNS } || words.any { it in setOf("трек", "песн", "песня", "композиц") } ||
                    text.contains("трек") || text.contains("песн") || text.contains("музык")) ->
                return skill(IntentType.MUSIC, mapOf("action" to "next"), text, 0.9f)

            words.any { it in setOf("предыдущий", "предыдущая", "назад") } &&
                (words.any { it in MUSIC_NOUNS || it in setOf("трек", "песня") } || text.contains("музык")) ->
                return skill(IntentType.MUSIC, mapOf("action" to "previous"), text, 0.9f)

            text.contains("что играет") || text.contains("какой трек") ||
                (words.contains("что") && words.any { it in MUSIC_NOUNS }) ->
                return skill(IntentType.MUSIC, mapOf("action" to "status"), text, 0.9f)

            // «включи музыку» без названия: продолжить то, что играло последним
            words.any { it in MUSIC_PLAY_VERBS } && words.size == 2 && words.any { it in MUSIC_NOUNS } ->
                return skill(IntentType.MUSIC, mapOf("action" to "resume"), text, 0.92f)

            // избранное / любимые / мои треки
            words.any { it in ON_VERBS } && (
                text.contains("избранн") || text.contains("любим") ||
                    text.contains("мои трек") || text.contains("мою музык") ||
                    (text.contains("моё") && text.contains("избран"))
                ) ->
                return skill(
                    IntentType.MUSIC,
                    mapOf("action" to "play", "scope" to "favorites"),
                    text,
                    0.93f,
                )

            // скачанное / оффлайн
            words.any { it in ON_VERBS } && (
                text.contains("скачан") || text.contains("оффлайн") || text.contains("офлайн") ||
                    text.contains("offline") || text.contains("загруженн")
                ) ->
                return skill(
                    IntentType.MUSIC,
                    mapOf("action" to "play", "scope" to "downloaded"),
                    text,
                    0.93f,
                )

            // альбом X
            words.any { it in ON_VERBS } && words.any { it.startsWith("альбом") } -> {
                val query = extractAfter(words, ON_VERBS + setOf("альбом", "альбома", "альбомчик"))
                return skill(
                    IntentType.MUSIC,
                    buildMap {
                        put("action", "play")
                        put("scope", "album")
                        if (query.isNotBlank()) put("query", query)
                    },
                    text,
                    0.92f,
                )
            }

            // исполнитель / артист / песни X
            words.any { it in ON_VERBS } && (
                words.any { it.startsWith("исполнител") || it.startsWith("артист") } ||
                    text.contains("песни ") || text.contains("треки ") ||
                    text.contains("что-нибудь от") || text.contains("что нибудь от")
                ) -> {
                val query = when {
                    text.contains("от ") -> text.substringAfter("от ").trim()
                    else -> extractAfter(
                        words,
                        ON_VERBS + setOf(
                            "исполнителя", "исполнитель", "артиста", "артист",
                            "песни", "треки", "музыку",
                        ),
                    )
                }
                return skill(
                    IntentType.MUSIC,
                    buildMap {
                        put("action", "play")
                        put("scope", "artist")
                        if (query.isNotBlank()) put("query", query)
                    },
                    text,
                    0.92f,
                )
            }

            words.any { it in ON_VERBS } && (
                words.any { it in MUSIC_NOUNS } || words.any { it.startsWith("песн") } ||
                    words.contains("трек") || words.contains("музыку") || words.contains("композицию")
                ) -> {
                val query = words
                    .dropWhile { it !in ON_VERBS }
                    .drop(1)
                    .filterNot {
                        it in MUSIC_NOUNS || it.startsWith("песн") || it == "трек" ||
                            it == "музыку" || it == "композицию" || it == "мне"
                    }
                    .joinToString(" ")
                    .trim()
                return skill(
                    IntentType.MUSIC,
                    buildMap {
                        put("action", "play")
                        put("scope", "track")
                        if (query.isNotBlank()) put("query", query)
                    },
                    text,
                    0.92f,
                )
            }

            // «включи queen / metallica / блатной» — без слова «песню»
            words.any { it in MUSIC_PLAY_VERBS } && words.size >= 2 -> {
                val query = words
                    .dropWhile { it !in MUSIC_PLAY_VERBS }
                    .drop(1)
                    .filterNot { it in setOf("мне", "пожалуйста", "сейчас", "пожалуйста") }
                    .joinToString(" ")
                    .trim()
                if (query.isBlank() || query.length < 2) return null
                // Device toggles уже отсечены выше по route(); короткий мусор не берём
                if (query in setOf("свет", "вспышку", "вспышка", "звук")) return null
                return skill(
                    IntentType.MUSIC,
                    mapOf("action" to "play", "scope" to "track", "query" to query),
                    text,
                    0.88f,
                )
            }
        }
        return null
    }

    private fun extractAfter(words: List<String>, dropTokens: Set<String>): String =
        words.filterNot { it in dropTokens || it == "мне" || it == "пожалуйста" }
            .joinToString(" ")
            .trim()

    private fun knowledge(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        val trigger = words.any { it in KNOWLEDGE_VERBS } ||
            text.startsWith("кто такой") || text.startsWith("кто такая") ||
            text.startsWith("кто такие") || text.startsWith("кто это") ||
            text.startsWith("что такое") || text.startsWith("что это такое") ||
            text.startsWith("что за ") ||
            text.startsWith("расскажи про") || text.startsWith("расскажи о") ||
            text.startsWith("расскажи коротко") || text.startsWith("расскажи кратко") ||
            text.startsWith("коротко про") || text.startsWith("кратко про")
        if (!trigger) return null
        // Шутки / болтовня / мнения / «кто ты» — не knowledge (chat)
        if (words.any { it.startsWith("анекдот") || it == "шутку" || it == "joke" }) return null
        if (text == "кто ты" || text.startsWith("кто ты ")) return null
        if (text.contains("думаешь") || text.contains("мнение") || text.contains("как считаешь")) return null
        if (text.startsWith("как тебе")) return null
        // «почему …» без темы — часто follow-up; полный «почему небо голубое» оставляем chat/wiki через глаголы
        // Важно: «коротко/кратко» — ДО голого «расскажи про», иначе не сработает.
        val query = when {
            text.startsWith("кто такие") -> text.removePrefix("кто такие").trim()
            text.startsWith("кто такой") -> text.removePrefix("кто такой").trim()
            text.startsWith("кто такая") -> text.removePrefix("кто такая").trim()
            text.startsWith("кто это") -> text.removePrefix("кто это").trim()
            text.startsWith("что такое") -> text.removePrefix("что такое").trim()
            text.startsWith("что это такое") -> text.removePrefix("что это такое").trim()
            text.startsWith("что за ") -> text.removePrefix("что за ").trim()
            text.startsWith("расскажи коротко про") -> text.removePrefix("расскажи коротко про").trim()
            text.startsWith("расскажи кратко про") -> text.removePrefix("расскажи кратко про").trim()
            text.startsWith("расскажи коротко о") -> text.removePrefix("расскажи коротко о").trim()
            text.startsWith("расскажи кратко о") -> text.removePrefix("расскажи кратко о").trim()
            text.startsWith("коротко про") -> text.removePrefix("коротко про").trim()
            text.startsWith("кратко про") -> text.removePrefix("кратко про").trim()
            text.startsWith("расскажи про") -> text.removePrefix("расскажи про").trim()
            text.startsWith("расскажи о") -> text.removePrefix("расскажи о").trim()
            else -> words.dropWhile { it !in KNOWLEDGE_VERBS }.drop(1).joinToString(" ").trim()
                .removePrefix("про ").removePrefix("о ").removePrefix("коротко ").removePrefix("кратко ")
                .removePrefix("про ").trim()
        }
        // «расскажи коротко про X» — when-ветки выше; если попали в else с «коротко про …»
        val cleanedQuery = query
            .removePrefix("коротко про ").removePrefix("кратко про ")
            .removePrefix("коротко о ").removePrefix("кратко о ")
            .trim()
        if (cleanedQuery.isBlank()) {
            return BrainResult.Clarify(question = "О чём рассказать?", confidence = 0.5f)
        }
        return skill(IntentType.KNOWLEDGE, mapOf("query" to cleanedQuery), text, 0.88f)
    }

    private fun extractCityAfter(words: List<String>, markers: Set<String>): String? {
        val idx = words.indexOfFirst { it in markers }
        if (idx < 0 || idx >= words.lastIndex) return null
        val city = words.drop(idx + 1).takeWhile { it !in WEATHER_WORDS }.joinToString(" ").trim()
        return normalizeCity(city)?.takeIf { it.length >= 2 }
    }

    /** Грубий нормализатор падежей для частых городов. */
    private fun normalizeCity(raw: String): String? {
        if (raw.isBlank()) return null
        val c = raw.lowercase().trim()
        val known = mapOf(
            "москве" to "Москва", "москвы" to "Москва", "москву" to "Москва", "москва" to "Москва",
            "питере" to "Санкт-Петербург", "питера" to "Санкт-Петербург",
            "петербурге" to "Санкт-Петербург", "петербург" to "Санкт-Петербург",
            "спб" to "Санкт-Петербург",
            "казани" to "Казань", "казань" to "Казань",
            "новосибирске" to "Новосибирск", "новосибирск" to "Новосибирск",
            "екб" to "Екатеринбург", "екатеринбурге" to "Екатеринбург", "екатеринбург" to "Екатеринбург",
            "сочи" to "Сочи",
            "минске" to "Минск", "минск" to "Минск",
            "киеве" to "Киев", "киев" to "Киев", "києві" to "Київ",
            "донецке" to "Донецк", "донецка" to "Донецк", "донецк" to "Донецк",
            "питере" to "Санкт-Петербург", "питера" to "Санкт-Петербург", "питер" to "Санкт-Петербург",
        )
        known[c]?.let { return it }
        // убираем типичные окончания предложного/родительного
        val stemmed = when {
            c.endsWith("е") && c.length > 4 -> c.dropLast(1)
            c.endsWith("у") && c.length > 4 -> c.dropLast(1)
            c.endsWith("ы") && c.length > 4 -> c.dropLast(1) + "а"
            else -> c
        }
        return stemmed.replaceFirstChar { it.uppercase() }
    }

    private fun screenshot(words: List<String>, request: UserRequest): BrainResult? {
        if (words.none { it in SCREENSHOT_WORDS } && !words.joinToString(" ").contains("скрин")) return null
        return skill(IntentType.SCREENSHOT, emptyMap(), words.joinToString(" "), 0.92f)
    }

    private fun clipboard(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        if (!text.contains("буфер") && words.none { it in setOf("скопируй", "скопировать") }) return null
        return if (words.any { it in setOf("скопируй", "скопировать") }) {
            val body = words.dropWhile { it !in setOf("скопируй", "скопировать") }.drop(1).joinToString(" ")
            skill(IntentType.CLIPBOARD, mapOf("action" to "set", "text" to body), text, 0.85f)
        } else {
            skill(IntentType.CLIPBOARD, mapOf("action" to "get"), text, 0.85f)
        }
    }

    private fun notifications(words: List<String>, request: UserRequest): BrainResult? {
        val text = words.joinToString(" ")
        // «ответь в уведомление …» / «ответь на уведомление от Telegram: ок»
        val replyHit = text.contains("ответь") && text.contains("уведомлен")
        if (replyHit) {
            val replyText = extractNotificationReplyText(text)
                ?: return BrainResult.Clarify(
                    question = "Что ответить в уведомление?",
                    confidence = 0.55f,
                )
            val query = extractNotificationQuery(text)
            return skill(
                IntentType.NOTIFICATIONS,
                buildMap {
                    put("action", "reply")
                    put("text", replyText)
                    if (query != null) put("query", query)
                },
                text,
                0.88f,
            )
        }
        if (!text.contains("уведомлен")) return null
        // «открой уведомления» → шторка, уже в uiActions; здесь — прочитать список
        if (words.any { it in setOf("открой", "покажи") } && words.none { it in setOf("какие", "что", "прочитай") }) {
            return null
        }
        return skill(IntentType.NOTIFICATIONS, mapOf("action" to "list"), text, 0.85f)
    }

    private fun extractNotificationReplyText(text: String): String? {
        val markers = listOf(
            "ответь в уведомление",
            "ответь на уведомление",
            "ответь уведомление",
        )
        val lower = text.lowercase()
        val marker = markers.firstOrNull { lower.contains(it) } ?: return null
        var rest = text.substringAfter(marker, missingDelimiterValue = "").trim()
        if (rest.startsWith(":")) rest = rest.drop(1).trim()
        // «от Telegram ок» → текст после последнего «:» или всё после query-слов
        val afterColon = rest.substringAfter(':', missingDelimiterValue = "").trim()
        if (afterColon.isNotBlank() && rest.contains(':')) return afterColon
        val dropQuery = rest
            .replace(Regex("""^(от\s+\S+\s+|в\s+\S+\s+)""", RegexOption.IGNORE_CASE), "")
            .trim()
        return dropQuery.takeIf { it.isNotBlank() && it.length >= 1 }
    }

    private fun extractNotificationQuery(text: String): String? {
        val m = Regex(
            """(?:от|в)\s+([A-Za-zА-Яа-яёЁ0-9_.\-]+)""",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    private fun skill(
        type: IntentType,
        slots: Map<String, Any>,
        text: String,
        confidence: Float,
    ): BrainResult.SkillCall =
        BrainResult.SkillCall(
            intent = Intent(
                type = type,
                normalizedText = text,
                slots = slots.mapValues { Slot(it.key, it.value) },
                confidence = confidence,
                confidenceLevel = if (confidence >= 0.9f) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
            ),
            confidence = confidence,
        )

    // ── helpers ────────────────────────────────────────────────────

    private fun extractMinutes(words: List<String>): Int? {
        val hours = if (words.any { it in HOUR_WORDS }) RuNumbers.before(words, HOUR_WORDS) ?: 1 else 0
        val half = if (words.contains("полчаса")) 30 else 0
        // «через 5 минут», «на десять минут», «на минуту»
        val minutes = if (words.any { it in MINUTE_WORDS }) RuNumbers.before(words, MINUTE_WORDS) ?: 1 else 0
        return (hours * 60 + half + minutes).takeIf { it > 0 }
    }

    private fun extractSeconds(words: List<String>): Int? =
        if (words.any { it in SECOND_WORDS }) RuNumbers.before(words, SECOND_WORDS) ?: 1 else null

    private fun looksLikeCommand(words: List<String>): Boolean =
        words.any { it in COMMAND_VERBS }

    private fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}% ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    companion object {
        // фонарик / свет
        private val LIGHT_NOUNS = setOf("фонарик", "фонарика", "фонарику", "фонариком", "фонарике", "фонарь", "свет", "подсветка", "подсветку", "подсветки", "освещение", "освещения")
        private val ON_VERBS = setOf("включи", "включить", "включите", "включил", "зажги", "зажечь", "зажигай", "дай", "дайте", "сделай", "сделайте")
        private val OFF_VERBS = setOf("выключи", "выключить", "выключите", "погаси", "погасить", "потуши", "потушить", "отключи", "отключить")

        // громкость
        private val VOLUME_UP = setOf(
            "громче", "прибавь", "прибавить", "увеличь", "увеличить", "погромче",
            "громкость", "звук", "повысь", "повысить", "прибавьте",
        )
        private val VOLUME_DOWN = setOf(
            "тише", "потише", "убавь", "убавить", "уменьши", "уменьшить",
            "приглуши", "приглушить", "сбавь", "сбавить",
        )
        private val VOLUME_MUTE = setOf("выключи звук", "без звука", "заглуши", "заглушить", "мут", "мама звук")
        private val VOLUME_UNMUTE = setOf("включи звук", "верни звук", "размуть")
        private val VOLUME_MAX = setOf("максимальную", "максимальная", "на максимум", "громкость на макс")

        // яркость экрана
        private val BRIGHTNESS_NOUNS = setOf("яркость", "ярче", "экран", "дисплей", "темнее", "тусклее")
        private val BRIGHT_UP = setOf("ярче", "прибавь", "прибавить", "увеличь", "увеличить", "максимальную", "максимальная", "поярче")
        private val BRIGHT_DOWN = setOf("темнее", "тусклее", "уменьши", "уменьшить", "приглуши", "приглушить")

        // таймер/будильник
        private val TIMER_NOUNS = setOf("таймер", "таймера", "таймере", "будильник", "секундомер")
        private val TIMER_CANCEL_VERBS = setOf(
            "отмени", "отменить", "сбрось", "останови", "выключи", "убери", "удали", "отключи",
        )
        private val ALARM_PM_WORDS = setOf("вечера", "вечером", "дня", "днём", "днем")
        private val PERCENT_WORDS = setOf("процент", "процента", "процентов")
        /** Отдельно от ON_VERBS: там «сделай» и «дай», а это не просьба включить трек. */
        private val MUSIC_PLAY_VERBS = setOf("включи", "включить", "включите", "поставь", "запусти", "играй")
        private val SETTINGS_SECTIONS = linkedMapOf(
            "батаре" to "battery", "экран" to "display", "дисплей" to "display", "звук" to "sound",
            "памят" to "storage", "хранилищ" to "storage", "приложени" to "apps",
            "геолокац" to "location", "местоположен" to "location", "уведомлен" to "notifications",
            "безопасност" to "security", "блютус" to "bluetooth", "блютуз" to "bluetooth",
            "bluetooth" to "bluetooth",
        )
        private val REMIND_VERBS = setOf("напомни", "напомнить", "напоминание")
        private val MINUTE_WORDS = setOf("минут", "минуту", "минуты", "минута")
        private val SECOND_WORDS = setOf("секунд", "секунду", "секунды", "секунда")
        private val HOUR_WORDS = setOf("час", "часа", "часов")

        // события календаря
        private val EVENT_VERBS = setOf("создай", "запланируй", "запиши", "создай заявку", "составь событие", "спланируй", "назначь")
        private val EVENT_NOUNS = setOf("событие", "события", "напоминание", "заявку")

        // сообщения
        private val SMS_VERBS = setOf("напиши", "отправь", "передай", "сообщение", "смс", "напечатай", "отправить", "отправь смс")
        private val TG_WRITE_VERBS = setOf("напиши", "напечатай", "отправь", "передай")

        // приложения
        private val LAUNCH_VERBS = setOf(
            "открой", "открыть", "запусти", "запустить", "включи приложение", "зайди", "перейди",
        )

        private val CALL_VERBS = setOf("позвони", "набери", "вызови", "звонок")
        private val CONTACT_WORDS = setOf("контакт", "контакты", "контактe")
        private val WIFI_WORDS = setOf("wifi", "wi-fi", "вайфай", "вай-фай")
        private val BT_WORDS = setOf("bluetooth", "блютус", "синезуб")
        private val AIRPLANE_WORDS = setOf("airplane", "авиарежим")
        private val SCREENSHOT_WORDS = setOf("скриншот", "screenshot", "снимок")
        private val WEATHER_WORDS = setOf(
            "погода", "погоду", "погоде", "погодой", "температура", "температуру",
            "дождь", "снег", "ветер", "прогноз", "облачно", "ясно",
        )
        private val MUSIC_NOUNS = setOf(
            "музыку", "музыка", "музыки", "плеер", "плеере", "spotify", "яндекс",
            "воспроизведение", "воспроизведения", "кион", "треки",
        )
        private val RESUME_VERBS = setOf(
            "продолжи", "продолжить", "продолжай", "возобнови", "возобновить",
            "продолжим", "продолжай",
        )
        private val KNOWLEDGE_VERBS = setOf(
            "расскажи", "рассказать", "объясни", "объяснить", "загугли",
        )

        // общие глаголы-команды (для неопределённых команд)
        private val COMMAND_VERBS = setOf(
            "включи", "выключи", "открой", "закрой", "сделай", "прибавь", "убавь",
            "напиши", "покажи", "запусти", "установи", "позвони", "отправь",
            "создай", "добавь", "удали", "прочитай", "переведи", "напомни",
            "нажми", "тапни", "пролистай", "скопируй",
        )
    }
}