package com.jarvis.brain.semantic

import com.jarvis.brain.ChatLlmEngine
import com.jarvis.brain.aiprovider.AICompletionRequest
import com.jarvis.brain.aiprovider.AICompletionStatus
import com.jarvis.brain.aiprovider.AIRouter
import com.jarvis.brain.aiprovider.AiTaskProfile
import com.jarvis.brain.aiprovider.AiTaskType
import com.jarvis.core.aioutput.StructuredAiAdapters
import com.jarvis.core.aioutput.StructuredAiOutputPipeline
import com.jarvis.core.aioutput.StructuredAiProcessResult
import com.jarvis.core.aioutput.StructuredAiTask
import com.jarvis.core.runtime.RouteDecision
import com.jarvis.core.runtime.semantic.AmbiguityLevel
import com.jarvis.core.runtime.semantic.SemanticClarity
import com.jarvis.core.runtime.semantic.SemanticInput
import com.jarvis.core.runtime.semantic.SemanticInterpreter
import com.jarvis.core.runtime.semantic.SemanticUnderstanding

/**
 * V2-02/V2-05: LLM structured semantic fallback via unified Structured AI Output.
 * Uses AIRouter with taskType=SEMANTIC_INTERPRETATION. Never executes tools.
 */
class LlmStructuredSemanticInterpreter(
    private val chatLlm: () -> ChatLlmEngine?,
    private val routerProvider: () -> AIRouter? = { null },
    private val isOnline: () -> Boolean = { false },
    private val preferCloud: () -> Boolean = { false },
    private val contextBuilder: com.jarvis.core.llm.UnifiedContextBuilder =
        com.jarvis.core.llm.UnifiedContextBuilder(),
    private val structuredPipeline: StructuredAiOutputPipeline = StructuredAiOutputPipeline(),
) : SemanticInterpreter {

    @Volatile var lastProviderId: String? = null
        private set
    @Volatile var lastModelId: String? = null
        private set
    @Volatile var lastSelectionReason: String? = null
        private set
    @Volatile var lastStructuredDiagnostics: com.jarvis.core.aioutput.StructuredAiDiagnostics? = null
        private set

    override fun interpret(input: SemanticInput): SemanticUnderstanding? {
        val prompt = buildString {
            appendLine(StructuredAiOutputPipeline.jsonInstruction(StructuredAiTask.SEMANTIC_INTERPRETATION))
            appendLine("Не выполняй действие. Только классификация.")
            append("Запрос: ").append(input.normalizedText.take(200))
            input.previousUserText?.takeIf { it.isNotBlank() }?.let {
                append(" | prev_user=").append(it.take(80))
            }
            input.lastToolId?.let { append(" | last_tool=").append(it) }
        }
        val out = generateStructured(prompt, input) ?: return null
        if (out.isBlank()) return null
        return parse(out, input)
    }

    private fun generateStructured(prompt: String, input: SemanticInput): String? {
        val router = routerProvider()
        val requestId = "sem-${System.nanoTime()}"
        val assembled = contextBuilder.build(
            com.jarvis.core.llm.ContextBuildInput(
                userText = prompt,
                kind = com.jarvis.core.llm.LlmContextKind.SEMANTIC_INTERPRETATION,
                budget = com.jarvis.core.llm.LlmContextBudget.FAST,
                persona = "DEVELOPER",
                conversationLines = listOfNotNull(
                    input.previousUserText?.takeIf { it.isNotBlank() }?.let { "user: ${it.take(80)}" },
                ),
                cloudMemoryAllowed = false,
            ),
        )
        if (router != null) {
            val profile = AiTaskProfile.forSemanticInterpretation(
                isOnline = isOnline(),
                preferCloud = preferCloud(),
                requestId = requestId,
            )
            val (sel, result) = router.complete(
                profile,
                AICompletionRequest(
                    requestId = requestId,
                    userText = prompt,
                    history = assembled.historyLines,
                    memories = "",
                    persona = "DEVELOPER",
                    wantStructured = true,
                    taskType = AiTaskType.SEMANTIC_INTERPRETATION,
                    assembledContext = assembled,
                ),
            )
            lastProviderId = sel.provider?.id
            lastModelId = result.modelId ?: sel.modelId
            lastSelectionReason = sel.selectionReason
            if (result.status == AICompletionStatus.SUCCESS) {
                return result.text?.trim()
            }
        }
        val engine = chatLlm() ?: return null
        if (!engine.isReady()) return null
        return runCatching {
            engine.generate(
                userText = prompt,
                history = emptyList(),
                memories = "",
                persona = "DEVELOPER",
            )
        }.getOrNull()?.trim()
    }

    internal fun parse(raw: String, input: SemanticInput): SemanticUnderstanding? {
        val processed = structuredPipeline.processWithRepair(
            raw = raw,
            task = StructuredAiTask.SEMANTIC_INTERPRETATION,
            requestId = null,
            repairFn = null, // repair needs another LLM call; orchestrated by caller if needed
        )
        lastStructuredDiagnostics = when (processed) {
            is StructuredAiProcessResult.Valid -> processed.diagnostics
            is StructuredAiProcessResult.Invalid -> processed.diagnostics
        }
        when (processed) {
            is StructuredAiProcessResult.Valid -> {
                val u = StructuredAiAdapters.toSemanticUnderstanding(
                    processed.envelope,
                    input.rawText,
                    input.normalizedText,
                )
                return u.copy(
                    notes = u.notes + listOf(
                        "ai_provider=${lastProviderId ?: "-"}",
                        "ai_model=${lastModelId ?: "-"}",
                        "ai_reason=${lastSelectionReason ?: "-"}",
                    ),
                )
            }
            is StructuredAiProcessResult.Invalid -> {
                // Legacy V2-02 line format fallback (compatibility)
                return parseLegacyLine(raw, input)?.also {
                    lastStructuredDiagnostics = processed.diagnostics.copy(
                        validationReason = (processed.diagnostics.validationReason ?: "") + "+legacy_fallback",
                    )
                }
            }
        }
    }

    /** Pre-V2-05 `route=...;goal=...` line protocol. */
    internal fun parseLegacyLine(raw: String, input: SemanticInput): SemanticUnderstanding? {
        val line = raw.lineSequence().firstOrNull { it.contains("route=") } ?: raw.lines().firstOrNull().orEmpty()
        if (line.isBlank()) return null
        val map = line.split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.take(i).trim().lowercase() to part.drop(i + 1).trim()
            }
            .toMap()
        if (!map.containsKey("route")) return null
        val route = when (map["route"]?.uppercase()) {
            "FAST_SKILL" -> RouteDecision.FAST_SKILL
            "CHAT" -> RouteDecision.CHAT
            "PLAN" -> RouteDecision.PLAN
            "CLARIFY" -> RouteDecision.CLARIFY
            "TEMPLATE" -> RouteDecision.TEMPLATE
            else -> RouteDecision.CHAT
        }
        val ambiguity = when (map["ambiguity"]?.uppercase()) {
            "NONE" -> AmbiguityLevel.NONE
            "LOW" -> AmbiguityLevel.LOW
            "MEDIUM" -> AmbiguityLevel.MEDIUM
            "HIGH" -> AmbiguityLevel.HIGH
            else -> AmbiguityLevel.LOW
        }
        val goal = map["goal"]?.takeIf { it.isNotBlank() } ?: "llm_interpreted"
        val intent = map["intent"]?.takeIf { it.isNotBlank() } ?: route.name.lowercase()
        val clarify = map["clarify"]?.takeIf { it.isNotBlank() }
        return SemanticUnderstanding(
            rawInput = input.rawText,
            normalizedInput = input.normalizedText,
            goal = goal.take(80),
            intent = intent.take(64),
            confidence = 0.65f,
            ambiguity = ambiguity,
            clarity = if (ambiguity >= AmbiguityLevel.MEDIUM) {
                SemanticClarity.PARTIAL
            } else {
                SemanticClarity.CLEAR
            },
            suggestedRoute = route,
            routeReason = "llm_semantic_legacy",
            clarifyQuestion = clarify,
            llmInterpretationUsed = true,
            notes = listOf(
                "llm_raw=${line.take(120)}",
                "ai_provider=${lastProviderId ?: "-"}",
                "ai_model=${lastModelId ?: "-"}",
                "ai_reason=${lastSelectionReason ?: "-"}",
                "structured_legacy=true",
            ),
            requiredCapabilities = when (route) {
                RouteDecision.CHAT -> listOf("chat")
                RouteDecision.PLAN -> listOf("planner", "discovery")
                RouteDecision.FAST_SKILL -> listOf("skill")
                else -> emptyList()
            },
        )
    }
}
