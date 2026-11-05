package com.jarvis.brain

import android.os.SystemClock
import android.util.Log
import com.jarvis.core.common.model.ConfidenceLevel
import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.Slot
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.nlp.Transliterator
import com.jarvis.core.privacy.SensitivityClassifier
import com.google.ai.edge.litertlm.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * LLM-backed Brain Router (JARVIS_BRAIN_ARCHITECTURE §2).
 * Uses LiteRT-LM with FunctionGemma (small on-device model: litert-community
 * functiongemma-mobile-actions_q8_ekv1024 ~284MB) for intent classification.
 *
 * The model is a function-calling specialist. Available skills are encoded as
 * `<start_function_declaration>` blocks in the developer/system turn, the user
 * phrase is sent as a normal turn, and the model answers with either
 * `<start_function_call>call:name{args}<end_function_call>` (→ SkillCall) or plain
 * text (→ delegated to the rule-based fallback for clarify/chat decisions).
 * Falls back to [RuleBasedBrain] when the model is unavailable or fails.
 *
 * Engine is initialized lazily and kept resident ("warm") for fast inference.
 */
class LlmBrain(
    private val modelPath: String,
    private val fallback: BrainRouter = RuleBasedBrain(),
    private val cacheDir: String? = null,
    /** Локальный подбор инструмента занимает 8+ с: при доступном облаке он только мешает. */
    private val cloudAvailable: () -> Boolean = { false },
) : BrainRouter {

    private val lock = ReentrantLock()
    private val promptCacheLock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var initialized = false
    @Volatile private var initFailed = false
    @Volatile private var cachedPromptKey: String = ""
    @Volatile private var cachedPrompt: String? = null

    /**
     * Initialize the engine (blocking, up to ~10s).
     * Must be called once on a background thread before first route() call.
     * Safe to call multiple times — only initializes once.
     */
    fun initialize() {
        lock.withLock {
            if (initialized || initFailed) return
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir
                )
                val eng = Engine(config).also { it.initialize() }
                engine = eng
                initialized = true
                Log.i(TAG, "LiteRT-LM engine initialized: $modelPath")
                runCatching { warmUp(eng) }
            } catch (e: Exception) {
                initFailed = true
                Log.e(TAG, "Failed to initialize LiteRT-LM: ${e.message}", e)
            }
        }
    }

    /** Single-flight lazy init на brain-потоке (если warm при старте не вызывали). */
    private fun ensureInitializedLazy() {
        if (initialized || initFailed) return
        Log.i(TAG, "lazy initialize FunctionGemma on first ambiguous route")
        initialize()
    }

    private fun warmUp(eng: Engine) {
        val t0 = SystemClock.elapsedRealtime()
        val config = ConversationConfig(
            systemInstruction = Contents.of(FUNCTION_CALLING_TRIGGER),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            maxOutputToken = 8,
        )
        eng.createConversation(config).use { conv ->
            conv.sendMessage("ping")
        }
        Log.i(TAG, "LLM warm-up done in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun route(
        request: UserRequest,
        history: List<String>,
        topSkills: List<BrainRouter.SkillInfo>
    ): BrainResult {
        val t0 = SystemClock.elapsedRealtime()

        // Deterministic-first: большинство команд не должны ждать LLM.
        val rules = fallback.route(request, history, topSkills)
        if (cloudAvailable()) return rules
        when (rules) {
            is BrainResult.SkillCall if rules.confidence >= RULES_SKILL_FAST -> {
                Log.d(TAG, "fast rules-skill '${SensitivityClassifier.mask(request.text)}' -> ${rules.intent.type} ${elapsed(t0)}ms")
                return rules
            }
            is BrainResult.Chat if rules.confidence >= RULES_CHAT_FAST -> {
                // Чат → DialogueResponder (+ Chat LLM). FunctionGemma здесь не нужен.
                Log.d(TAG, "fast rules-chat '${SensitivityClassifier.mask(request.text)}' ${elapsed(t0)}ms")
                return rules
            }
            is BrainResult.Clarify if rules.confidence >= RULES_CLARIFY_FAST -> {
                // Не хватает слота («кому?») — LLM не угадает; не тратим инференс.
                Log.d(TAG, "fast rules-clarify '${SensitivityClassifier.mask(request.text)}' ${elapsed(t0)}ms")
                return rules
            }
            else -> Unit
        }

        if (!initialized && !initFailed) {
            // Ambiguous path: поднять FunctionGemma один раз (brain-thread).
            ensureInitializedLazy()
        }
        if (!initialized || initFailed) {
            Log.d(TAG, "LLM not ready, using fallback ${elapsed(t0)}ms")
            return rules
        }

        val eng = engine ?: return rules

        return try {
            val raw = callModel(eng, request.text, topSkills, history)
            val parsed = parseFunctionCalls(raw, topSkills, request.text)
            Log.d(
                TAG,
                "llm ${elapsed(t0)}ms user='${SensitivityClassifier.mask(request.text)}' " +
                    "out='${SensitivityClassifier.mask(raw).take(160)}' skills=${topSkills.map { it.id }} " +
                    "hit=${parsed != null}",
            )
            parsed ?: run {
                Log.d(TAG, "No function call in model output, delegate to fallback")
                rules
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM route failed: ${e.message}")
            rules
        }
    }

    override fun continueAfterTool(
        originalRequest: UserRequest,
        history: List<String>,
        topSkills: List<BrainRouter.SkillInfo>,
        lastToolId: String,
        lastToolMessage: String,
    ): BrainResult? {
        if (!initialized || initFailed) return null
        val eng = engine ?: return null
        // Только UI/launcher цепочки — иначе лишний инференс
        if (lastToolId != "launcher" && !lastToolId.startsWith("ui.") &&
            lastToolId != "system.setting"
        ) {
            return null
        }
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val followUp =
                "Original user request: ${originalRequest.text}\n" +
                    "Last tool $lastToolId result: $lastToolMessage\n" +
                    "If another function is needed to finish the request, call it. " +
                    "If done, reply with DONE."
            val raw = callModel(eng, followUp, topSkills, history)
            Log.d(TAG, "react ${elapsed(t0)}ms out='${raw.take(160)}'")
            if (raw.contains("DONE", ignoreCase = true) &&
                !raw.contains("<start_function_call>")
            ) {
                return null
            }
            parseFunctionCalls(raw, topSkills, originalRequest.text)
        } catch (e: Exception) {
            Log.e(TAG, "react failed: ${e.message}")
            null
        }
    }

    private fun elapsed(t0: Long): Long = SystemClock.elapsedRealtime() - t0

    /**
     * Shut down the engine to free memory.
     */
    fun shutdown() {
        lock.withLock {
            runCatching { engine?.close() }
            engine = null
            initialized = false
            Log.i(TAG, "LiteRT-LM engine shut down")
        }
    }

    /** Whether the LLM engine is ready to serve requests. */
    fun isReady(): Boolean = initialized && !initFailed

    // ── model interaction ──────────────────────────────────────────

    private fun callModel(
        eng: Engine,
        userText: String,
        topSkills: List<BrainRouter.SkillInfo>,
        history: List<String> = emptyList(),
    ): String {
        val skillKey = topSkills.joinToString("|") { it.id }
        val systemPrompt = synchronized(promptCacheLock) {
            if (skillKey == cachedPromptKey && cachedPrompt != null) {
                cachedPrompt!!
            } else {
                buildSystemPrompt(topSkills).also {
                    cachedPromptKey = skillKey
                    cachedPrompt = it
                }
            }
        }

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        )

        val payload = buildString {
            val recent = history.takeLast(4)
            if (recent.isNotEmpty()) {
                append("Context:\n")
                recent.forEach { append(it).append('\n') }
            }
            append(userText)
        }

        eng.createConversation(config).use { conv ->
            val response = conv.sendMessage(payload)
            return response.contents.text() ?: ""
        }
    }

    // ── FunctionGemma prompt formatting ────────────────────────────

    /**
     * Builds the developer (system) turn with `<start_function_declaration>` blocks.
     * Format per Google FunctionGemma docs:
     *   <start_function_declaration>declaration:NAME{description:<escape>…<escape>,
     *     parameters:{properties:{P:{description:…,type:…,enum:[…]},…},required:[…],type:OBJECT}}
     *     <end_function_declaration>
     */
    private fun buildSystemPrompt(skills: List<BrainRouter.SkillInfo>): String = buildString {
        append(FUNCTION_CALLING_TRIGGER)
        for (skill in skills) {
            append("<start_function_declaration>")
            append("declaration:").append(functionName(skill.id)).append("{")
            append("description:<escape>").append(escapeValue(skill.description)).append("<escape>,")
            append("parameters:{properties:{")
            for (p in skill.parameters) {
                append(p.name).append(":{")
                append("description:<escape>").append(escapeValue(p.description)).append("<escape>,")
                if (p.allowedValues.isNotEmpty()) {
                    append("enum:[")
                    append(p.allowedValues.joinToString(",") { "<escape>$it<escape>" })
                    append("],")
                }
                append("type:<escape>").append(typeToGemma(p.type)).append("<escape>")
                append("},")
            }
            // trim trailing ","
            if (skill.parameters.isNotEmpty()) deleteCharAt(length - 1)
            append("},")
            append("required:[")
            append(
                skill.parameters.filter { it.required }.joinToString(",") { "<escape>${it.name}<escape>" }
            )
            append("],")
            append("type:<escape>OBJECT<escape>")
            append("}}")
            append("<end_function_declaration>")
        }
    }

    private fun functionName(skillId: String): String = skillId.replace('.', '_')

    private fun escapeValue(v: String): String =
        v.replace("\\", "\\\\").replace("<escape>", " ")

    private fun typeToGemma(type: String): String = when (type.uppercase()) {
        "INTEGER", "INT" -> "INTEGER"
        "BOOLEAN", "BOOL" -> "BOOLEAN"
        "NUMBER" -> "NUMBER"
        else -> "STRING"
    }

    // ── FunctionGemma output parsing ───────────────────────────────

    /**
     * Parses `<start_function_call>call:name{arg:v,arg:<escape>s<escape>}<end_function_call>`
     * from the model output. Returns null when there is no usable call.
     */
    private fun parseFunctionCalls(
        raw: String,
        topSkills: List<BrainRouter.SkillInfo>,
        userText: String
    ): BrainResult? {
        val calls = extractToolCalls(raw)
        if (calls.isEmpty()) return null

        val first = calls[0]
        val skill = matchSkill(first.name, topSkills) ?: run {
            Log.w(TAG, "Unknown function name from LLM: ${first.name}")
            return null
        }

        val args = mutableMapOf<String, Any>()
        for ((k, v) in first.arguments) {
            args[k] = castValue(v)
        }
        sanitizeArgs(skill, args, userText)

        val intentType = resolveIntentType(skill.id)
        if (intentType == IntentType.UNKNOWN) {
            Log.w(TAG, "Unknown skill from LLM: ${skill.id}")
            return null
        }

        return BrainResult.SkillCall(
            intent = Intent(
                type = intentType,
                normalizedText = raw.take(200),
                slots = args.map { (k, v) -> k to Slot(k, v) }.toMap(),
                confidence = CONFIDENCE_SKILL_CALL,
                confidenceLevel = ConfidenceLevel.HIGH
            ),
            confidence = CONFIDENCE_SKILL_CALL
        )
    }

    /**
     * The FunctionGemma model is English-centric and may echo placeholders
     * (`[app_name]`) or hallucinate values for Russian phrases. Drop placeholder
     * arguments and, for app launching, recover the app name from the raw text.
     */
    private fun sanitizeArgs(
        skill: BrainRouter.SkillInfo,
        args: MutableMap<String, Any>,
        userText: String
    ) {
        args.entries.removeAll { it.value is String && PLACEHOLDER_REGEX.matches(it.value as String) }

        if (skill.id == APP_LAUNCH_SKILL) {
            val current = args["app"]?.toString().orEmpty()
            if (current.isBlank() || !userTextMentions(userText, current)) {
                val fromText = extractAppName(userText)
                if (fromText.isNotBlank()) {
                    Log.d(TAG, "app arg '$current' not grounded in text, using '$fromText'")
                    args["app"] = fromText
                }
            }
        }

        // FunctionGemma часто ставит action=toggle вместо on/off.
        if (skill.id.contains("flashlight", ignoreCase = true)) {
            val action = args["action"]?.toString()?.lowercase().orEmpty()
            if (action !in setOf("on", "off")) {
                val lower = userText.lowercase()
                val mapped = when {
                    OFF_HINTS.any { it in lower } -> "off"
                    ON_HINTS.any { it in lower } -> "on"
                    else -> null
                }
                if (mapped != null) {
                    Log.d(TAG, "flashlight action '$action' remapped to '$mapped' from text")
                    args["action"] = mapped
                }
            }
        }
    }

    /** Whether [value] (as said by the user, any script) occurs in [userText]. */
    private fun userTextMentions(userText: String, value: String): Boolean {
        val haystack = Transliterator.normalize(userText)
        val needle = Transliterator.normalize(value)
        return needle.isNotEmpty() && haystack.contains(needle)
    }

    /** Recover the app name as the words following a launch verb. */
    private fun extractAppName(userText: String): String {
        val words = userText.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim('.', ',', '!', '?', '«', '»', '"', '\'') }
            .filter { it.isNotBlank() }
        val idx = words.indexOfFirst { it in LAUNCH_VERBS }
        if (idx < 0) return ""
        return words.drop(idx + 1).joinToString(" ").trim()
    }

    /** Extracts (name, arguments) pairs using the official FunctionGemma regex. */
    private fun extractToolCalls(text: String): List<FunctionCall> {
        val calls = mutableListOf<FunctionCall>()
        CALL_REGEX.findAll(text).forEach { m ->
            val name = m.groupValues[1]
            val argsStr = m.groupValues[2]
            val args = mutableMapOf<String, String>()
            ARG_REGEX.findAll(argsStr).forEach { a ->
                val key = a.groupValues[1]
                val value = if (a.groupValues[2].isNotEmpty()) a.groupValues[2] else a.groupValues[3]
                if (key.isNotEmpty()) args[key] = value.trim()
            }
            calls.add(FunctionCall(name, args))
        }
        return calls
    }

    private data class FunctionCall(val name: String, val arguments: Map<String, String>)

    private fun matchSkill(
        fnName: String,
        skills: List<BrainRouter.SkillInfo>
    ): BrainRouter.SkillInfo? = skills.firstOrNull { functionName(it.id) == fnName }

    private fun castValue(v: String): Any = when {
        v.startsWith("<escape>") && v.endsWith("</escape>") ->
            v.removePrefix("<escape>").removeSuffix("</escape>")
        v.toIntOrNull() != null -> v.toInt()
        v.toDoubleOrNull() != null -> v.toDouble().roundToInt()
        v.equals("true", ignoreCase = true) -> true
        v.equals("false", ignoreCase = true) -> false
        else -> v.trim('"', '\'', ' ')
    }

    private fun resolveIntentType(skillId: String): IntentType = when {
        skillId.contains("flashlight", true) -> IntentType.FLASHLIGHT
        skillId.contains("volume", true) -> IntentType.VOLUME
        skillId.contains("brightness", true) -> IntentType.BRIGHTNESS
        skillId.contains("timer", true) -> IntentType.TIMER
        skillId.contains("system.status", true) -> IntentType.SYSTEM_STATUS
        skillId.contains("event", true) || skillId.contains("calendar", true) -> IntentType.CREATE_EVENT
        skillId.contains("sms", true) -> IntentType.SEND_SMS
        skillId.contains("telegram", true) -> IntentType.SEND_TELEGRAM
        skillId == "app.launch" || (skillId.contains("launch", true) && skillId.contains("app", true)) -> IntentType.LAUNCH_APP
        skillId.contains("system", true) || skillId.contains("wifi", true) || skillId.contains("bluetooth", true) -> IntentType.SYSTEM_SETTING
        skillId.contains("phone.call", true) || skillId.contains("call", true) && skillId.contains("phone", true) -> IntentType.CALL
        skillId.contains("contacts", true) -> IntentType.CONTACTS
        skillId.contains("files", true) -> IntentType.FILES
        skillId.contains("ui.click", true) -> IntentType.UI_CLICK
        skillId.contains("ui.type", true) -> IntentType.UI_TYPE
        skillId.contains("ui.swipe", true) -> IntentType.UI_SWIPE
        skillId.contains("ui.global", true) -> IntentType.UI_GLOBAL
        skillId.contains("ui.read", true) || skillId.contains("screen.read", true) -> IntentType.UI_READ
        skillId.contains("ui.wait", true) -> IntentType.UI_WAIT
        skillId.contains("notification", true) -> IntentType.NOTIFICATIONS
        skillId.contains("clipboard", true) -> IntentType.CLIPBOARD
        skillId.contains("screenshot", true) -> IntentType.SCREENSHOT
        skillId.contains("weather", true) || skillId.contains("погод", true) -> IntentType.WEATHER
        skillId.contains("music", true) || skillId.contains("media", true) -> IntentType.MUSIC
        skillId.contains("knowledge", true) || skillId.contains("wiki", true) -> IntentType.KNOWLEDGE
        skillId.contains("setting", true) -> IntentType.SYSTEM_SETTING
        skillId.contains("launch", true) || skillId.contains("app", true) -> IntentType.LAUNCH_APP
        else -> IntentType.UNKNOWN
    }

    /**
     * Extract the concatenated text from a message's contents structure.
     * AKA content blocks can be Text or others — we take all Text blocks.
     */
    private fun Contents?.text(): String {
        if (this == null) return ""
        return contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }

    companion object {
        private const val TAG = "Jarvis.LlmBrain"

        /** Function-call короткий — 48 токенов хватает на call+args. */
        private const val MAX_OUTPUT_TOKENS = 48

        /** Rules → tool без LLM. */
        private const val RULES_SKILL_FAST = 0.82f
        /** Smalltalk / chat — DialogueResponder, не FunctionGemma. */
        private const val RULES_CHAT_FAST = 0.75f
        /** Clarify со слотом — тоже без LLM. */
        private const val RULES_CLARIFY_FAST = 0.45f

        private const val CONFIDENCE_SKILL_CALL = 0.95f

        private const val APP_LAUNCH_SKILL = "app.launch"

        private val PLACEHOLDER_REGEX = Regex("^\\[.+\\]$")

        private val LAUNCH_VERBS = setOf(
            "открой", "открыть", "открывай",
            "запусти", "запустить", "запускай",
            "включи", "включить",
            "покажи", "показать",
            "загрузи", "загрузить",
            "launch", "open", "start", "run"
        )

        private val ON_HINTS = listOf("включи", "зажги", "включить", "turn on", "on")
        private val OFF_HINTS = listOf("выключи", "погаси", "потуши", "отключи", "выключить", "turn off", "off")

        /** Prompt-based trigger required by FunctionGemma to enable tool using. */
        private const val FUNCTION_CALLING_TRIGGER =
            "You are a model that can do function calling with the following functions"

        private val CALL_REGEX = Regex(
            "<start_function_call>call:(\\w+)\\{(.*?)\\}<end_function_call>",
            RegexOption.DOT_MATCHES_ALL
        )

        private val ARG_REGEX = Regex(
            "(\\w+):(?:<escape>(.*?)<escape>|([^,}]*))",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}