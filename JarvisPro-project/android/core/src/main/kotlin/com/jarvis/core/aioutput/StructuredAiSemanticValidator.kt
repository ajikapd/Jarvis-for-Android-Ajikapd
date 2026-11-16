package com.jarvis.core.aioutput

import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.execution.CatalogToolRegistry
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.ActionPlanValidator
import com.jarvis.core.planner.PlanStep
import com.jarvis.core.planner.PlanStepKind

/**
 * V2-05: semantic / capability validation after schema OK.
 * Unknown tools / bad args → fail (no execution).
 */
object StructuredAiSemanticValidator {

    fun validate(
        envelope: StructuredAiEnvelope,
        toolRegistry: CatalogToolRegistry? = null,
        task: StructuredAiTask = StructuredAiTask.CHAT,
    ): SemanticValidationResult {
        // LLM cannot self-assert security outcomes via payload fields — already rejected as unknown keys;
        // additionally strip/forbid dangerous argument keys.
        when (envelope.responseType) {
            StructuredAiResponseType.ANSWER -> {
                if (envelope.text.isNullOrBlank()) {
                    return SemanticValidationResult.Fail("empty_answer")
                }
                if (looksLikeExecutionClaim(envelope.text) && task != StructuredAiTask.CHAT) {
                    return SemanticValidationResult.Fail("unverified_execution_claim")
                }
            }
            StructuredAiResponseType.CLARIFICATION -> {
                if (envelope.question.isNullOrBlank()) {
                    return SemanticValidationResult.Fail("empty_question")
                }
                if (envelope.clarificationReason == null) {
                    return SemanticValidationResult.Fail("missing_reason")
                }
            }
            StructuredAiResponseType.TOOL_REQUEST -> {
                val toolId = envelope.toolId?.trim().orEmpty()
                if (toolId.isBlank()) return SemanticValidationResult.Fail("missing_toolId")
                val argFail = forbiddenArgs(envelope.arguments)
                if (argFail != null) return SemanticValidationResult.Fail(argFail)
                if (toolRegistry != null) {
                    val def = toolRegistry.findDefinition(toolId)
                        ?: return SemanticValidationResult.Fail("INVALID_TOOL:$toolId")
                    val argsFail = validateAgainstDefinition(def, envelope.arguments)
                    if (argsFail != null) return SemanticValidationResult.Fail(argsFail)
                }
            }
            StructuredAiResponseType.PLAN -> {
                if (envelope.goal.isNullOrBlank()) {
                    return SemanticValidationResult.Fail("missing_goal")
                }
                if (envelope.planSteps.isEmpty()) {
                    return SemanticValidationResult.Fail("empty_plan")
                }
                for (step in envelope.planSteps) {
                    val kind = runCatching { PlanStepKind.valueOf(step.kind) }.getOrNull()
                        ?: return SemanticValidationResult.Fail("invalid_step_kind:${step.kind}")
                    if (kind == PlanStepKind.TOOL) {
                        val tid = step.toolId?.trim().orEmpty()
                        if (tid.isBlank()) return SemanticValidationResult.Fail("plan_tool_missing")
                        val argFail = forbiddenArgs(step.parameters)
                        if (argFail != null) return SemanticValidationResult.Fail(argFail)
                        if (toolRegistry != null && toolRegistry.findDefinition(tid) == null) {
                            return SemanticValidationResult.Fail("INVALID_TOOL:$tid")
                        }
                    }
                }
                val plan = StructuredAiAdapters.toActionPlanDraft(envelope)
                    ?: return SemanticValidationResult.Fail("plan_adapt_failed")
                val v = ActionPlanValidator.validate(plan)
                if (!v.ok) {
                    return SemanticValidationResult.Fail("plan_invalid:${v.errors.firstOrNull()}")
                }
            }
            StructuredAiResponseType.NO_ACTION -> {
                if (!envelope.toolId.isNullOrBlank() || envelope.planSteps.isNotEmpty()) {
                    return SemanticValidationResult.Fail("no_action_executable_payload")
                }
            }
            StructuredAiResponseType.CONFIRMATION_REQUIRED -> {
                if (envelope.confirmationSummary.isNullOrBlank() && envelope.text.isNullOrBlank()) {
                    return SemanticValidationResult.Fail("empty_confirmation_summary")
                }
                val argFail = forbiddenArgs(envelope.arguments)
                if (argFail != null) return SemanticValidationResult.Fail(argFail)
                // Note: this is a *suggestion* only — AutonomyPolicy decides confirmation.
            }
        }
        return SemanticValidationResult.Ok(envelope)
    }

    private fun forbiddenArgs(args: Map<String, String>): String? {
        for (k in args.keys) {
            val lk = k.lowercase()
            if (StructuredAiLimits.FORBIDDEN_ARG_KEYS.any { lk == it || lk.contains(it) }) {
                return "forbidden_arg:$k"
            }
        }
        return null
    }

    private fun validateAgainstDefinition(
        def: ToolDefinition,
        args: Map<String, String>,
    ): String? {
        val known = def.parameters.map { it.name }.toSet()
        if (known.isNotEmpty()) {
            for (k in args.keys) {
                if (k !in known) return "unknown_arg:$k"
            }
            for (p in def.parameters) {
                if (p.required && args[p.name].isNullOrBlank()) {
                    return "missing_required_arg:${p.name}"
                }
                val v = args[p.name] ?: continue
                when (p.type) {
                    ParameterType.BOOLEAN ->
                        if (v !in setOf("true", "false", "0", "1", "on", "off")) {
                            return "invalid_arg_type:${p.name}"
                        }
                    ParameterType.INTEGER ->
                        if (v.toIntOrNull() == null) return "invalid_arg_type:${p.name}"
                    ParameterType.ENUM ->
                        if (p.allowedValues.isNotEmpty() && v !in p.allowedValues) {
                            return "invalid_enum:${p.name}"
                        }
                    ParameterType.STRING -> Unit
                }
            }
        }
        return null
    }

    private fun looksLikeExecutionClaim(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("готово") || t.contains("выполнил") || t.contains("открыл") ||
            t.contains("успешно")) &&
            (t.contains("tool") || t.contains("инструмент") || t.contains("выполнил действие"))
    }
}

sealed class SemanticValidationResult {
    data class Ok(val envelope: StructuredAiEnvelope) : SemanticValidationResult()
    data class Fail(val reason: String) : SemanticValidationResult()
}

/**
 * Adapters to existing runtime contracts — no execution.
 */
object StructuredAiAdapters {

    fun toProposedAction(envelope: StructuredAiEnvelope): com.jarvis.core.autonomy.ProposedAction? {
        if (envelope.responseType != StructuredAiResponseType.TOOL_REQUEST &&
            envelope.responseType != StructuredAiResponseType.CONFIRMATION_REQUIRED
        ) {
            return null
        }
        val toolId = envelope.toolId ?: return null
        // Prefer mapper for known tools (preserves ActionKind); fallback LOCAL_DEVICE_SAFE only if mapped.
        val mapped = com.jarvis.core.autonomy.ProposedActionMapper.fromToolId(
            toolId = toolId,
            actionId = toolId,
            args = envelope.arguments,
            displaySummary = envelope.text
                ?: envelope.confirmationSummary
                ?: envelope.goal
                ?: toolId,
        )
        if (mapped != null) {
            // Ensure args are exactly the validated map (immutable binding snapshot)
            return mapped.copy(arguments = envelope.arguments)
        }
        // Known only via registry / LLM id — bind with UNKNOWN kind (policy must classify conservatively).
        return com.jarvis.core.autonomy.ProposedAction(
            actionId = toolId,
            kind = com.jarvis.core.autonomy.ActionKind.UNKNOWN,
            displaySummary = (envelope.goal ?: toolId).take(160),
            toolId = toolId,
            arguments = envelope.arguments,
        )
    }

    fun toActionPlanDraft(
        envelope: StructuredAiEnvelope,
        planId: String = "llm-plan-${System.nanoTime()}",
        goalId: String = "goal-${System.nanoTime()}",
    ): ActionPlan? {
        if (envelope.responseType != StructuredAiResponseType.PLAN) return null
        val steps = envelope.planSteps.map { draft ->
            val kind = PlanStepKind.valueOf(draft.kind)
            PlanStep(
                stepId = draft.stepId,
                kind = kind,
                toolId = draft.toolId,
                parameters = draft.parameters,
                dependsOn = draft.dependsOn,
                requiresConfirmation = draft.requiresConfirmation,
                displaySummary = draft.displaySummary,
                waitMs = draft.waitMs,
            )
        }
        return ActionPlan(
            planId = planId,
            goalId = goalId,
            status = ActionPlanStatus.READY,
            steps = steps,
            notes = listOf("source=structured_ai_v${envelope.schemaVersion}"),
        )
    }

    fun toSemanticUnderstanding(
        envelope: StructuredAiEnvelope,
        rawInput: String,
        normalizedInput: String,
    ): com.jarvis.core.runtime.semantic.SemanticUnderstanding {
        val route = when (envelope.suggestedRoute?.uppercase()) {
            "FAST_SKILL" -> com.jarvis.core.runtime.RouteDecision.FAST_SKILL
            "CHAT" -> com.jarvis.core.runtime.RouteDecision.CHAT
            "PLAN" -> com.jarvis.core.runtime.RouteDecision.PLAN
            "CLARIFY" -> com.jarvis.core.runtime.RouteDecision.CLARIFY
            "TEMPLATE" -> com.jarvis.core.runtime.RouteDecision.TEMPLATE
            else -> when (envelope.responseType) {
                StructuredAiResponseType.CLARIFICATION ->
                    com.jarvis.core.runtime.RouteDecision.CLARIFY
                StructuredAiResponseType.PLAN ->
                    com.jarvis.core.runtime.RouteDecision.PLAN
                StructuredAiResponseType.TOOL_REQUEST ->
                    com.jarvis.core.runtime.RouteDecision.FAST_SKILL
                StructuredAiResponseType.NO_ACTION ->
                    com.jarvis.core.runtime.RouteDecision.CHAT
                else -> com.jarvis.core.runtime.RouteDecision.CHAT
            }
        }
        val ambiguity = when (envelope.ambiguity?.uppercase()) {
            "NONE" -> com.jarvis.core.runtime.semantic.AmbiguityLevel.NONE
            "LOW" -> com.jarvis.core.runtime.semantic.AmbiguityLevel.LOW
            "MEDIUM" -> com.jarvis.core.runtime.semantic.AmbiguityLevel.MEDIUM
            "HIGH" -> com.jarvis.core.runtime.semantic.AmbiguityLevel.HIGH
            else -> if (envelope.responseType == StructuredAiResponseType.CLARIFICATION) {
                com.jarvis.core.runtime.semantic.AmbiguityLevel.HIGH
            } else {
                com.jarvis.core.runtime.semantic.AmbiguityLevel.LOW
            }
        }
        val goal = envelope.goal?.take(80)
            ?: envelope.toolId
            ?: envelope.responseType.name.lowercase()
        val intent = envelope.intent?.take(64) ?: route.name.lowercase()
        return com.jarvis.core.runtime.semantic.SemanticUnderstanding(
            rawInput = rawInput,
            normalizedInput = normalizedInput,
            goal = goal,
            intent = intent,
            confidence = envelope.confidence ?: 0.65f,
            ambiguity = ambiguity,
            clarity = if (ambiguity >= com.jarvis.core.runtime.semantic.AmbiguityLevel.MEDIUM) {
                com.jarvis.core.runtime.semantic.SemanticClarity.PARTIAL
            } else {
                com.jarvis.core.runtime.semantic.SemanticClarity.CLEAR
            },
            suggestedRoute = route,
            routeReason = "structured_ai_v${envelope.schemaVersion}",
            clarifyQuestion = envelope.question,
            llmInterpretationUsed = true,
            notes = listOf(
                "structured_type=${envelope.responseType}",
                "schema=${envelope.schemaVersion}",
            ),
            requiredCapabilities = when (route) {
                com.jarvis.core.runtime.RouteDecision.CHAT -> listOf("chat")
                com.jarvis.core.runtime.RouteDecision.PLAN -> listOf("planner", "discovery")
                com.jarvis.core.runtime.RouteDecision.FAST_SKILL -> listOf("skill")
                else -> emptyList()
            },
        )
    }
}
