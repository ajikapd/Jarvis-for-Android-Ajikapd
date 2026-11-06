package com.jarvis.core.runtime.semantic

import com.jarvis.core.runtime.RouteDecision

/**
 * V2-02: быстрый локальный semantic classifier (без LLM).
 * Покрывает paraphrases, obvious templates, PLAN signals, clarify pronouns.
 */
class FastSemanticClassifier : SemanticInterpreter {
    private companion object {
        /** Обращение и вежливость не меняют команду: «джарвис, включи, пожалуйста, фонарик». */
        val POLITE_WORDS = Regex("(^|[\\s,])(джарвис|слушай|пожалуйста)(?=$|[\\s,])")
        val SPACES = Regex("[\\s,]{2,}")
    }


    override fun interpret(input: SemanticInput): SemanticUnderstanding? {
        val raw = input.rawText.trim()
        val norm = input.normalizedText.trim().ifBlank { raw }
        val spoken = norm.lowercase().trim().trimEnd('?', '!', '.', '…')
        val t = spoken.replace(POLITE_WORDS, " ").replace(SPACES, " ").trim().ifBlank { spoken }
        if (t.isBlank()) {
            return understanding(
                input, raw, norm,
                goal = "empty",
                intent = "clarify",
                confidence = 0.2f,
                ambiguity = AmbiguityLevel.HIGH,
                clarity = SemanticClarity.UNCLEAR,
                route = RouteDecision.CLARIFY,
                reason = "empty_input",
                clarify = "Что сделать?",
            )
        }

        // Напоминание разбирает Brain: «напомни купить хлеб и молоко» — не план из шагов.
        if (t.contains("напомни") || t.contains("напоминан")) return null

        // Pronouns / vague without resolveable context → CLARIFY
        pronounAmbiguity(t, input)?.let { return it.copy(rawInput = raw, normalizedInput = norm) }

        // Obvious short templates
        obviousTemplate(t, raw, norm)?.let { return it }

        // Conversational CHAT (must beat weak template / skill matching)
        if (looksConversational(t)) {
            return understanding(
                input, raw, norm,
                goal = "conversation",
                intent = "chat",
                confidence = 0.8f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.CHAT,
                reason = "conversational_nl",
                caps = listOf("chat"),
            )
        }

        // Multi-step / free-form PLAN
        planGoal(t, raw, norm, input)?.let { return it }

        // Fast skill paraphrases
        volumeIntent(t, raw, norm)?.let { return it }
        flashlightIntent(t, raw, norm)?.let { return it }
        launcherIntent(t, raw, norm)?.let { return it }
        brightnessIntent(t, raw, norm)?.let { return it }
        weatherIntent(t, raw, norm)?.let { return it }
        musicIntent(t, raw, norm)?.let { return it }

        // Soft incompleteness
        if (isIncompleteAction(t)) {
            return understanding(
                input, raw, norm,
                goal = "incomplete_action",
                intent = "clarify",
                confidence = 0.55f,
                ambiguity = AmbiguityLevel.MEDIUM,
                clarity = SemanticClarity.PARTIAL,
                route = RouteDecision.CLARIFY,
                reason = "incomplete_action",
                clarify = targetedIncompleteQuestion(t),
                candidates = incompleteCandidates(t),
            )
        }

        // Unknown → null so Orchestrator can use BrainRouter / LLM chat
        return null
    }

    private fun obviousTemplate(t: String, raw: String, norm: String): SemanticUnderstanding? {
        // Only short, unambiguous phrases — conversational wrappers skip TEMPLATE
        if (hasConversationalWrapper(t)) return null

        if (isShortTimeQuery(t)) {
            return understanding(
                SemanticInput(raw, norm), raw, norm,
                goal = "tell_time",
                intent = "template_time",
                confidence = 0.95f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.TEMPLATE,
                reason = "obvious_time",
            )
        }
        if (isShortDateQuery(t)) {
            return understanding(
                SemanticInput(raw, norm), raw, norm,
                goal = "tell_date",
                intent = "template_date",
                confidence = 0.95f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.TEMPLATE,
                reason = "obvious_date",
            )
        }
        if (isShortGreeting(t)) {
            return understanding(
                SemanticInput(raw, norm), raw, norm,
                goal = "greet",
                intent = "template_greeting",
                confidence = 0.95f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.TEMPLATE,
                reason = "obvious_greeting",
            )
        }
        if (isShortThanks(t) || isShortBye(t)) {
            return understanding(
                SemanticInput(raw, norm), raw, norm,
                goal = if (isShortThanks(t)) "thanks" else "goodbye",
                intent = if (isShortThanks(t)) "template_thanks" else "template_goodbye",
                confidence = 0.95f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.TEMPLATE,
                reason = if (isShortThanks(t)) "obvious_thanks" else "obvious_goodbye",
            )
        }
        if (isShortIdentity(t)) {
            return understanding(
                SemanticInput(raw, norm), raw, norm,
                goal = "identity",
                intent = "template_identity",
                confidence = 0.9f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.TEMPLATE,
                reason = "obvious_identity",
            )
        }
        if (isShortJoke(t)) {
            return understanding(
                SemanticInput(raw, norm), raw, norm,
                goal = "joke",
                intent = "template_joke",
                confidence = 0.9f,
                ambiguity = AmbiguityLevel.NONE,
                clarity = SemanticClarity.CLEAR,
                route = RouteDecision.TEMPLATE,
                reason = "obvious_joke",
            )
        }
        return null
    }

    private fun pronounAmbiguity(t: String, input: SemanticInput): SemanticUnderstanding? {
        val barePronoun = t in setOf(
            "открой его", "открой это", "включи это", "включи его",
            "выключи это", "закрой это", "сделай нормально", "сделай так",
            "этот", "тот", "его", "её", "ее", "их", "туда", "сюда",
        ) || t.matches(Regex("""^(открой|включи|выключи|закрой|запусти)\s+(его|её|ее|это|тот|тот же)$"""))
        if (!barePronoun) return null
        // Only skip CLARIFY when there is a resolvable referent (last tool / concrete prior target).
        // A bare previous utterance is not enough to bind «его/это».
        val hasResolvableCtx = !input.lastToolId.isNullOrBlank() ||
            looksLikeConcreteTarget(input.previousUserText)
        if (hasResolvableCtx) {
            // Let FollowUpResolver / BrainRouter resolve with context — not force CLARIFY
            return null
        }
        return understanding(
            input, input.rawText, input.normalizedText,
            goal = "resolve_reference",
            intent = "clarify",
            confidence = 0.7f,
            ambiguity = AmbiguityLevel.HIGH,
            clarity = SemanticClarity.UNCLEAR,
            route = RouteDecision.CLARIFY,
            reason = "unresolved_pronoun",
            clarify = when {
                t.contains("открой") || t.contains("запусти") ->
                    "Что именно открыть? Назови приложение или файл."
                t.contains("включи") ->
                    "Что включить — фонарик, музыку или что-то другое?"
                t.contains("нормально") ->
                    "Уточни: сделать громче, ярче или что-то ещё?"
                else -> "Уточни, пожалуйста: о чём именно речь?"
            },
            candidates = listOf("flashlight", "music", "launcher", "volume"),
            refs = listOf("pronoun"),
        )
    }

    private fun planGoal(
        t: String,
        raw: String,
        norm: String,
        input: SemanticInput,
    ): SemanticUnderstanding? {
        val multiVerb = countActionVerbs(t) >= 2
        val conjunction = t.contains(" и ") || t.contains(" потом ") ||
            t.contains(" затем ") || t.contains(",")
        val goalish = listOf(
            "найди", "найди мне", "подготов", "сравни", "помоги",
            "организ", "запланир", "собери", "купить", "билет",
            "дешёв", "дешев", "вариант", "поездке", "поездк",
        ).any { t.contains(it) }
        val browserish = t.contains("браузер") || t.contains("в интернете") ||
            t.contains("в гугле") || t.contains("погугл")
        val stepsHint = when {
            !multiVerb && !conjunction -> 0 // «найди в интернете рецепт борща» — один поиск, не план.
            multiVerb && (conjunction || goalish) -> 2
            goalish && browserish -> 2
            goalish && conjunction -> 2
            else -> 0
        }
        if (stepsHint < 2) return null

        val caps = buildList {
            if (browserish || t.contains("найди") || t.contains("билет")) add("browser")
            if (t.contains("открой") || t.contains("запусти")) add("launcher")
            if (t.contains("отправ") || t.contains("напиши") || t.contains("telegram")) add("messaging")
            if (t.contains("сравни")) add("reasoning")
            if (isEmpty()) {
                add("planner")
                add("discovery")
            }
        }.distinct()

        return understanding(
            input, raw, norm,
            goal = "multi_step_goal",
            intent = "plan",
            confidence = 0.75f,
            ambiguity = AmbiguityLevel.LOW,
            clarity = SemanticClarity.PARTIAL,
            route = RouteDecision.PLAN,
            reason = "free_form_multi_step",
            caps = caps,
            constraints = extractConstraints(t),
            notes = listOf("steps=$stepsHint", "goalish=$goalish"),
        )
    }

    private fun volumeIntent(t: String, raw: String, norm: String): SemanticUnderstanding? {
        val upPhrases = listOf(
            "громче", "погромче", "прибавь звук", "прибавь громкость",
            "увеличь громкость", "сделай громче", "давай погромче",
            "звук побольше", "побольше звук", "volume up",
        )
        val downPhrases = listOf(
            "тише", "потише", "убавь звук", "убавь громкость",
            "уменьши громкость", "сделай тише", "сделай потише",
            "здесь потише", "что-то тихо", "слишком громко",
            "звук поменьше", "volume down", "сделай здесь потише",
        )
        val up = upPhrases.any { phrase ->
            t == phrase || t.startsWith("$phrase ") || (t.contains(phrase) && t.length < 48)
        }
        val down = downPhrases.any { phrase ->
            t == phrase || t.startsWith("$phrase ") || (t.contains(phrase) && t.length < 56)
        }
        val mute = t in setOf("без звука", "выключи звук", "мьют", "mute") ||
            t.contains("выключи звук")
        if (!up && !down && !mute) return null
        // Conversational about volume → CHAT
        if (hasConversationalWrapper(t) && t.length > 50) return null
        val action = when {
            mute -> "mute"
            up -> "up"
            else -> "down"
        }
        val rewrite = when (action) {
            "mute" -> "выключи звук"
            "up" -> "громче"
            else -> "тише"
        }
        return understanding(
            SemanticInput(raw, norm), raw, norm,
            goal = if (action == "up") "increase_audio_volume" else "reduce_audio_volume",
            intent = "volume",
            confidence = 0.88f,
            ambiguity = AmbiguityLevel.NONE,
            clarity = SemanticClarity.CLEAR,
            route = RouteDecision.FAST_SKILL,
            reason = "semantic_volume_$action",
            caps = listOf("volume"),
            args = mapOf("action" to action),
            rewrite = rewrite,
            entities = listOf(SemanticEntity("action", action, "volume_action")),
        )
    }

    private fun flashlightIntent(t: String, raw: String, norm: String): SemanticUnderstanding? {
        val on = listOf(
            "включи фонарик", "включить фонарик", "фонарик включи",
            "свет фонарик", "включи свет", "фонарик",
        ).any { t == it || t.startsWith(it) }
        val off = listOf(
            "выключи фонарик", "выключить фонарик", "фонарик выключи",
            "выключи свет",
        ).any { t == it || t.startsWith(it) }
        if (!on && !off) {
            if (t.contains("фонарик") && t.length < 40) {
                return understanding(
                    SemanticInput(raw, norm), raw, norm,
                    goal = "control_flashlight",
                    intent = "flashlight",
                    confidence = 0.7f,
                    ambiguity = AmbiguityLevel.MEDIUM,
                    clarity = SemanticClarity.PARTIAL,
                    route = RouteDecision.CLARIFY,
                    reason = "flashlight_ambiguous",
                    clarify = "Включить фонарик или выключить?",
                    candidates = listOf("включи фонарик", "выключи фонарик"),
                    caps = listOf("flashlight"),
                )
            }
            return null
        }
        val action = if (off) "off" else "on"
        return understanding(
            SemanticInput(raw, norm), raw, norm,
            goal = if (action == "on") "enable_flashlight" else "disable_flashlight",
            intent = "flashlight",
            confidence = 0.92f,
            ambiguity = AmbiguityLevel.NONE,
            clarity = SemanticClarity.CLEAR,
            route = RouteDecision.FAST_SKILL,
            reason = "semantic_flashlight_$action",
            caps = listOf("flashlight"),
            args = mapOf("action" to action),
            rewrite = if (action == "on") "включи фонарик" else "выключи фонарик",
        )
    }

    private fun launcherIntent(t: String, raw: String, norm: String): SemanticUnderstanding? {
        val open = t.startsWith("открой ") || t.startsWith("запусти ") ||
            t.startsWith("открой") || t.startsWith("запусти")
        if (!open) return null
        if (countActionVerbs(t) >= 2 && (t.contains(" и ") || t.contains("найди"))) {
            return null // PLAN path
        }
        val app = t.removePrefix("открой ").removePrefix("запусти ").trim()
        if (app.isBlank() || app in setOf("его", "это", "её", "ее")) return null
        return understanding(
            SemanticInput(raw, norm), raw, norm,
            goal = "launch_app",
            intent = "launcher",
            confidence = 0.85f,
            ambiguity = AmbiguityLevel.NONE,
            clarity = SemanticClarity.CLEAR,
            route = RouteDecision.FAST_SKILL,
            reason = "semantic_launcher",
            caps = listOf("launcher"),
            args = mapOf("action" to "open", "app" to app),
            rewrite = "открой $app",
            entities = listOf(SemanticEntity("app", app, "target")),
        )
    }

    private fun brightnessIntent(t: String, raw: String, norm: String): SemanticUnderstanding? {
        val up = t.contains("ярче") || t.contains("прибавь яркость")
        val down = t.contains("тусклее") || t.contains("убавь яркость") || t.contains("темнее экран")
        if (!up && !down) return null
        val action = if (up) "up" else "down"
        return understanding(
            SemanticInput(raw, norm), raw, norm,
            goal = "adjust_brightness",
            intent = "brightness",
            confidence = 0.85f,
            ambiguity = AmbiguityLevel.NONE,
            clarity = SemanticClarity.CLEAR,
            route = RouteDecision.FAST_SKILL,
            reason = "semantic_brightness_$action",
            caps = listOf("brightness"),
            args = mapOf("action" to action),
            rewrite = if (up) "ярче" else "тусклее",
        )
    }

    private fun weatherIntent(t: String, raw: String, norm: String): SemanticUnderstanding? {
        if (!t.contains("погод")) return null
        if (hasConversationalWrapper(t) && t.length > 60) return null
        return understanding(
            SemanticInput(raw, norm), raw, norm,
            goal = "weather_query",
            intent = "weather",
            confidence = 0.8f,
            ambiguity = AmbiguityLevel.LOW,
            clarity = SemanticClarity.CLEAR,
            route = RouteDecision.FAST_SKILL,
            reason = "semantic_weather",
            caps = listOf("weather"),
            rewrite = if (t.length < 40) t else "погода",
        )
    }

    private fun musicIntent(t: String, raw: String, norm: String): SemanticUnderstanding? {
        val play = t.contains("включи музык") || t.contains("играй") || t.startsWith("послушай")
        val stop = t.contains("выключи музык") || t.contains("останови музык") || t == "пауза"
        if (!play && !stop) return null
        val action = if (stop) "pause" else "play"
        return understanding(
            SemanticInput(raw, norm), raw, norm,
            goal = "music_control",
            intent = "music",
            confidence = 0.8f,
            ambiguity = AmbiguityLevel.LOW,
            clarity = SemanticClarity.CLEAR,
            route = RouteDecision.FAST_SKILL,
            reason = "semantic_music_$action",
            caps = listOf("music"),
            args = mapOf("action" to action),
            rewrite = if (stop) "выключи музыку" else "включи музыку",
        )
    }

    private fun looksConversational(t: String): Boolean {
        if (t.length < 8) return false
        val markers = listOf(
            "что ты думаешь", "что думаешь", "как думаешь", "расскажи",
            "объясни", "посоветуй", "почему", "зачем", "мне скучно",
            "как тебе", "стоит ли", "как ты относишься", "про квантов",
            "про космос", "про ии", "про ai", "что такое",
            "помоги понять", "разверни", "подробнее про",
        )
        if (markers.any { t.contains(it) }) return true
        if (hasConversationalWrapper(t) && !looksLikeDeviceCommand(t)) return true
        return false
    }

    private fun hasConversationalWrapper(t: String): Boolean {
        val wrappers = listOf(
            "как думаешь", "как тебе", "стоит ли", "и стоит",
            "мне кажется", "а что если", "не знаешь ли",
            "можешь ли сказать", "интересно",
        )
        return wrappers.any { t.contains(it) }
    }

    private fun looksLikeDeviceCommand(t: String): Boolean {
        val cmds = listOf(
            "фонарик", "громк", "тише", "открой", "запусти",
            "ярче", "погода", "музык", "таймер", "позвони",
        )
        return cmds.any { t.contains(it) }
    }

    private fun looksLikeConcreteTarget(prev: String?): Boolean {
        if (prev.isNullOrBlank()) return false
        val p = prev.lowercase()
        // Prior open/launch of a named app, or a named entity after open verb
        if ((p.contains("открой ") || p.contains("запусти ")) &&
            p.split(Regex("\\s+")).size >= 2
        ) {
            val target = p.removePrefix("открой ").removePrefix("запусти ").trim()
            if (target.isNotBlank() && target !in setOf("его", "это", "её", "ее", "тот")) return true
        }
        return listOf("telegram", "браузер", "chrome", "youtube", "настройк", "фонарик")
            .any { p.contains(it) }
    }

    private fun isShortTimeQuery(t: String): Boolean {
        if (t.length > 36) return false
        return t in setOf(
            "который час", "сколько времени", "сколько время",
            "текущее время", "время",
        ) || t.startsWith("который час") || t.startsWith("сколько времени")
    }

    private fun isShortDateQuery(t: String): Boolean {
        if (t.length > 40) return false
        return t.contains("какая дата") || t.contains("какое число") ||
            t.contains("какой сегодня день") || t == "сегодня какая дата"
    }

    private fun isShortGreeting(t: String): Boolean {
        if (t.length > 28) return false
        return t in setOf(
            "привет", "здравствуй", "здравствуйте", "добрый день",
            "добрый вечер", "доброе утро", "хай", "hello", "hi",
        )
    }

    private fun isShortThanks(t: String): Boolean =
        t.length <= 24 && (t == "спасибо" || t == "благодарю" || t.startsWith("спасибо "))

    private fun isShortBye(t: String): Boolean =
        t.length <= 24 && (t == "пока" || t == "до свидания" || t == "увидимся")

    private fun isShortIdentity(t: String): Boolean {
        if (t.length > 32) return false
        return t in setOf("кто ты", "ты кто", "как тебя зовут", "что ты такое") ||
            t.startsWith("кто ты ") || t.startsWith("ты кто ")
    }

    private fun isShortJoke(t: String): Boolean {
        if (t.length > 36) return false
        return t in setOf("пошути", "шутка", "анекдот", "расскажи шутку", "расскажи анекдот")
    }

    private fun isIncompleteAction(t: String): Boolean {
        val incomplete = setOf(
            "включи", "выключи", "открой", "запусти", "поставь",
            "найди", "сделай", "отправь", "напиши",
        )
        return t in incomplete
    }

    private fun targetedIncompleteQuestion(t: String): String = when (t) {
        "включи" -> "Что включить — фонарик, музыку или что-то другое?"
        "выключи" -> "Что выключить — фонарик, музыку или звук?"
        "открой", "запусти" -> "Какое приложение открыть?"
        "найди" -> "Что найти?"
        "отправь", "напиши" -> "Кому и что написать?"
        "сделай" -> "Что именно сделать?"
        else -> "Что именно сделать, сэр?"
    }

    private fun incompleteCandidates(t: String): List<String> = when (t) {
        "включи" -> listOf("включи фонарик", "включи музыку")
        "выключи" -> listOf("выключи фонарик", "выключи музыку", "тише")
        "открой", "запусти" -> listOf("открой Telegram", "открой настройки")
        else -> emptyList()
    }

    private fun countActionVerbs(t: String): Int {
        val verbs = listOf(
            "открой", "запусти", "найди", "отправь", "напиши",
            "включи", "выключи", "сравни", "подготов", "скачай",
            "купить", "забронируй", "покажи",
        )
        return verbs.count { t.contains(it) }
    }

    private fun extractConstraints(t: String): List<String> {
        val out = mutableListOf<String>()
        if (t.contains("завтра")) out += "when=tomorrow"
        if (t.contains("сегодня")) out += "when=today"
        if (t.contains("дешёв") || t.contains("дешев")) out += "prefer=cheapest"
        Regex("""в\s+([а-яёa-z\-]{3,})""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.let {
            if (it !in setOf("интернете", "браузере", "телеграме")) out += "place=$it"
        }
        return out
    }

    private fun understanding(
        input: SemanticInput,
        raw: String,
        norm: String,
        goal: String,
        intent: String,
        confidence: Float,
        ambiguity: AmbiguityLevel,
        clarity: SemanticClarity,
        route: RouteDecision,
        reason: String,
        caps: List<String> = emptyList(),
        args: Map<String, String> = emptyMap(),
        rewrite: String? = null,
        clarify: String? = null,
        candidates: List<String> = emptyList(),
        constraints: List<String> = emptyList(),
        entities: List<SemanticEntity> = emptyList(),
        refs: List<String> = emptyList(),
        notes: List<String> = emptyList(),
        llm: Boolean = false,
    ) = SemanticUnderstanding(
        rawInput = raw,
        normalizedInput = norm,
        goal = goal,
        intent = intent,
        entities = entities,
        arguments = args,
        constraints = constraints,
        contextRefs = listOfNotNull(input.lastToolId),
        conversationRefs = refs,
        confidence = confidence,
        ambiguity = ambiguity,
        clarity = clarity,
        requiredCapabilities = caps,
        suggestedRoute = route,
        routeReason = reason,
        clarifyQuestion = clarify,
        candidateInterpretations = candidates,
        rewrittenCommand = rewrite,
        llmInterpretationUsed = llm,
        notes = notes,
    )
}
