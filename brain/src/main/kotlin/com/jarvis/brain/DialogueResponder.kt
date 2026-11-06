package com.jarvis.brain

import android.content.Context
import android.util.Log

/**
 * Диалог: micro fast-path → Chat LLM (generate) → шаблоны без модели.
 * Structured decide для чата не используем — на 0.5B/1.5B это +latency и хуже качество.
 *
 * V2-01: [respondDetailed] явно отличает TEMPLATE vs LLM (не выдаём template за conversation).
 */
open class DialogueResponder(
    private val context: Context?,
    private val persona: String = "CLASSIC",
    private val chatLlm: (() -> ChatLlmEngine?)? = null,
) {

    open fun respond(userText: String, history: List<String>, memories: String = ""): String =
        respondDetailed(userText, history, memories).text

    open fun respondDetailed(
        userText: String,
        history: List<String>,
        memories: String = "",
        assembled: com.jarvis.core.llm.AssembledLlmContext? = null,
    ): DialogueResult {
        val t = userText.lowercase().trim()
        val userTurns = history.mapNotNull { line ->
            if (line.startsWith("user:", ignoreCase = true)) {
                line.substringAfter(':').trim()
            } else {
                null
            }
        }
        val prevUser = userTurns.dropLast(1).lastOrNull().orEmpty()
        val lastAssistant = history.lastOrNull { it.startsWith("assistant:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()

        if (FollowUpResolver.looksLikeFollowUp(t) &&
            (t.contains("то же самое") || t.contains("тоже самое") ||
                t.contains("ещё раз") || t.contains("еще раз") ||
                t.contains("повтори") || t == "снова" ||
                t in setOf("завтра", "послезавтра", "сегодня") ||
                (t.startsWith("а ") &&
                    (t.contains("завтра") || t.contains("послезавтра") ||
                        t.contains("сегодня") || t.contains("погода") || t.contains("фонарик"))))
        ) {
            val text = if (prevUser.isBlank()) {
                "Пока нечего повторить, сэр."
            } else {
                "Уточните запрос целиком, сэр. Было: «${prevUser.take(60)}»."
            }
            return DialogueResult(text, DialogueResult.Kind.TEMPLATE, reason = "follow_up_clarify")
        }

        // V2-02: TEMPLATE только для коротких очевидных фраз.
        // Conversational wrappers («как думаешь, сколько времени…») → LLM CHAT.
        fun hasConversationalWrapper(): Boolean {
            val wrappers = listOf(
                "как думаешь", "как тебе", "стоит ли", "и стоит",
                "мне кажется", "а что если", "интересно", "можешь ли",
            )
            return wrappers.any { t.contains(it) }
        }
        fun shortEnough(max: Int) = t.length <= max && !hasConversationalWrapper()

        if (shortEnough(36) &&
            (t.contains("сколько времени") || t.contains("который час") || t.contains("сколько время") ||
                t == "время" || t == "текущее время")
        ) {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val text = "Сейчас $time, сэр."
            return DialogueResult(text, DialogueResult.Kind.TEMPLATE, reason = "time")
        }
        if (shortEnough(40) &&
            (t.contains("какая дата") || t.contains("какое число") || t.contains("какой сегодня день"))
        ) {
            val date = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale("ru"))
                .format(java.util.Date())
            val text = "Сегодня $date, сэр."
            return DialogueResult(text, DialogueResult.Kind.TEMPLATE, reason = "date")
        }

        fun has(vararg words: String) = words.any { t.contains(it) }
        if (shortEnough(28)) {
            when {
                t in setOf(
                    "привет", "здравствуй", "здравствуйте", "добрый день",
                    "добрый вечер", "доброе утро", "хай", "hello", "hi",
                ) || has("привет") && t.length <= 16 -> {
                    val text = when (persona) {
                        "FRIENDLY" -> "Привет-привет! Чем могу помочь?"
                        "MINIMAL" -> "Привет."
                        "DEVELOPER" -> "Приветствую. Готов к работе."
                        else -> com.jarvis.core.nlp.DailyBriefing.greeting(java.time.LocalTime.now().hour) + " Чем могу помочь?"
                    }
                    return DialogueResult(text, DialogueResult.Kind.TEMPLATE, reason = "greeting")
                }
                has("спасибо", "благодарю") && t.length <= 24 ->
                    return DialogueResult(
                        "Всегда к вашим услугам, сэр.",
                        DialogueResult.Kind.TEMPLATE,
                        reason = "thanks",
                    )
                has("пока", "до свидания") && t.length <= 24 ->
                    return DialogueResult(
                        "До встречи, сэр. Буду на связи.",
                        DialogueResult.Kind.TEMPLATE,
                        reason = "goodbye",
                    )
            }
        }

        if (shortEnough(32) &&
            (t == "кто ты" || t == "ты кто" || t.startsWith("кто ты ") || t.startsWith("ты кто ") ||
                t == "что ты" || t.startsWith("что ты такое") || t == "как тебя зовут")
        ) {
            val text = when (persona) {
                "FRIENDLY" -> "Привет! Я Джарвис, твой голосовой помощник. Всё работает локально."
                "MINIMAL" -> "Джарвис. Офлайн-ассистент."
                "DEVELOPER" -> "Jarvis · локальный ASR/TTS · Brain."
                else -> "Джарвис, сэр. Ваш личный ассистент."
            }
            return DialogueResult(text, DialogueResult.Kind.TEMPLATE, reason = "identity")
        }
        // «что ты умеешь» — short template OK; long conversational about capabilities → LLM
        if (shortEnough(40) && has("что ты умеешь", "что умеешь", "твои возможности")) {
            return DialogueResult(
                "Управляю телефоном и приложениями, сэр. Музыка, звонки, сообщения, погода, напоминания, маршруты, сводка на день. На вопросы тоже отвечаю.",
                DialogueResult.Kind.TEMPLATE,
                reason = "capabilities",
            )
        }
        if (shortEnough(36) &&
            (has("анекдот", "шутку", "пошути", "расскажи шутку") || t == "пошути")
        ) {
            return DialogueResult(pickJoke(), DialogueResult.Kind.TEMPLATE, reason = "joke")
        }

        fun isShortFollowUp(): Boolean {
            if (t.length > 56) return false
            return t in setOf(
                "подробнее", "продолжи", "дальше", "ещё", "еще", "и что", "ну и",
                "а это", "про это", "об этом", "а там", "почему", "зачем", "как так",
            ) || t.startsWith("а ") || t.startsWith("ну ") ||
                t.startsWith("а что") || t.startsWith("а как") || t.startsWith("а почему") ||
                t.startsWith("а зачем") || t.startsWith("расскажи ещё") || t.startsWith("расскажи еще")
        }

        val engine = chatLlm?.invoke()
        if (engine != null && engine.isReady()) {
            val llmUser = resolveWithContext(userText, prevUser, lastAssistant, isShortFollowUp())
            val t0 = System.nanoTime()
            val generated = runCatching {
                if (assembled != null) {
                    engine.generateWithContext(llmUser, persona, assembled)
                } else {
                    engine.generate(llmUser, history, memories, persona)
                }
            }.getOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (!generated.isNullOrBlank()) {
                runCatching {
                    Log.i(TAG, "llm-generate ${ms}ms loaded=${engine.isLoaded()} out='${generated.take(100)}'")
                }
                val routed = engine as? com.jarvis.brain.aiprovider.RoutedChatLlmEngine
                val budget = assembled?.outputBudget
                    ?: com.jarvis.core.llm.LlmContextBudget.BALANCED
                return DialogueResult(
                    text = com.jarvis.core.llm.LlmOutputPolicy.apply(generated, budget),
                    kind = DialogueResult.Kind.LLM,
                    reason = "llm_generate",
                    providerHint = routed?.lastProviderId ?: "chat_llm",
                    modelHint = routed?.lastModelId,
                    selectionReason = routed?.lastSelectionReason,
                    fallbackUsed = routed?.lastFallback ?: false,
                    latencyMs = routed?.lastLatencyMs ?: ms,
                    ttftMs = routed?.lastTtftMs,
                    ttftKind = routed?.lastTtftKind,
                    contextDiagnostics = assembled?.diagnostics,
                )
            }
            runCatching { Log.w(TAG, "llm empty after ${ms}ms — fallback") }
        }

        val fallback = templateFallback(t, prevUser, memories, lastAssistant)
        return DialogueResult(
            fallback,
            DialogueResult.Kind.FALLBACK_TEMPLATE,
            reason = "no_llm_or_empty",
        )
    }

    private fun pickJoke(): String {
        val jokes = listOf(
            "Программист заходит в лифт, а там написано «этаж 1…16». Он выбирает 17 — «а вдруг там баг».",
            "Говорит Wi‑Fi роутеру: «Ты меня слышишь?» Тот: «Пакет потерян».",
            "Почему дельфин не пишет код? Потому что у него нет «берега» ошибок — только океан.",
            "Спросил у будильника: «Который час?» Он: «Пора вставать». Классика.",
            "Оптимист изобретает самолёт, пессимист — парашют, инженер — инструкцию на 400 страниц.",
        )
        return jokes[(System.nanoTime() % jokes.size).toInt()]
    }

    private fun templateFallback(
        t: String,
        prevUser: String,
        memories: String,
        lastAssistant: String,
    ): String {
        fun has(vararg words: String) = words.any { t.contains(it) }
        if (t in setOf("это", "туда", "туда же", "сюда", "так же", "также") && prevUser.isNotBlank()) {
            return "Вы имеете в виду «$prevUser», сэр? Уточните командой целиком."
        }
        if (has("ты кто", "кто ты", "что ты")) {
            return when (persona) {
                "FRIENDLY" -> "Привет! Я Джарвис, твой голосовой помощник. Всё работает локально."
                "MINIMAL" -> "Джарвис. Офлайн-ассистент."
                "DEVELOPER" -> "Jarvis · локальный ASR/TTS · Brain."
                else -> "Джарвис, сэр. Ваш личный ассистент."
            }
        }
        if (has("что ты помнишь", "что помнишь", "моя память") && memories.isNotBlank()) {
            return "Из памяти: ${memories.take(280)}"
        }
        if (has("как меня зовут", "мое имя", "моё имя") && memories.isNotBlank()) {
            val nameHint = Regex("""(?i)(?:зовут|имя)[:\s]+([^\n,;.]+)""")
                .find(memories)?.groupValues?.get(1)?.trim()
            if (!nameHint.isNullOrBlank()) return "Вас зовут $nameHint, сэр."
            return "В памяти есть: ${memories.take(200)}"
        }
        if (has("что ты умеешь", "что умеешь")) {
            return "Управляю устройством и могу поболтать, если скачана модель диалога (Настройки → Модели)."
        }
        if (t in setOf("да", "нет", "ага", "угу", "давай") && lastAssistant.contains("?")) {
            return "Хорошо, сэр. Что сделать дальше?"
        }
        return "Для свободного разговора скачай «Диалог · Qwen 2.5 1.5B» в Настройки → Модели. " +
            "Команды вроде «включи фонарик» работают и без неё."
    }

    private fun resolveWithContext(
        userText: String,
        prevUser: String,
        lastAssistant: String,
        shortFollowUp: Boolean,
    ): String {
        if (prevUser.isBlank() && !shortFollowUp) return userText
        val topic = extractTopic(prevUser).ifBlank {
            extractTopic(lastAssistant)
        }
        var s = userText.trim()
        if (topic.isNotBlank()) {
            s = s.replace(Regex("""\bони\b""", RegexOption.IGNORE_CASE), topic)
                .replace(Regex("""\bих\b""", RegexOption.IGNORE_CASE), topic)
                .replace(Regex("""\bим\b""", RegexOption.IGNORE_CASE), topic)
                .replace(Regex("""\bэто\b""", RegexOption.IGNORE_CASE), topic)
                .replace(Regex("""\bнего\b""", RegexOption.IGNORE_CASE), topic)
                .replace(Regex("""\bнеё\b""", RegexOption.IGNORE_CASE), topic)
                .replace(Regex("""\bнее\b""", RegexOption.IGNORE_CASE), topic)
        }
        s = s.removePrefix("а ").removePrefix("А ").trim()
        return when {
            shortFollowUp && topic.isNotBlank() && !s.contains(topic, ignoreCase = true) ->
                "Вопрос про «$topic»: $s. Ответь по этой теме, коротко."
            shortFollowUp && prevUser.isNotBlank() ->
                "Продолжение темы «${prevUser.take(60)}»: $s"
            else -> s.ifBlank { userText }
        }
    }

    private fun extractTopic(prevUser: String): String {
        if (prevUser.isBlank()) return ""
        var s = prevUser.lowercase().trim()
        val prefixes = listOf(
            "расскажи коротко про ", "расскажи про ", "расскажи о ", "что такое ",
            "кто такие ", "кто такой ", "кто такая ", "кто это ", "почему ", "зачем ",
            "что думаешь про ", "что думаешь о ", "загугли ",
        )
        for (p in prefixes) {
            if (s.startsWith(p)) {
                s = s.removePrefix(p).trim()
                break
            }
        }
        return s.split(Regex("\\s+")).take(3).joinToString(" ").trim().take(40)
    }

    companion object {
        private const val TAG = "Jarvis.Dialogue"
    }
}
