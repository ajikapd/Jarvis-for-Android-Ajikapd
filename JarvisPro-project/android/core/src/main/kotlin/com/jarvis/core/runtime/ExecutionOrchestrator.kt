package com.jarvis.core.runtime

import com.jarvis.core.autonomy.ActionIntegrity
import com.jarvis.core.autonomy.ActionRiskClass
import com.jarvis.core.autonomy.ActionRiskClassifier
import com.jarvis.core.autonomy.AutonomyActionPipeline
import com.jarvis.core.autonomy.AutonomyLevel
import com.jarvis.core.autonomy.AutonomyPlanStatus
import com.jarvis.core.autonomy.DecisionSource
import com.jarvis.core.common.PolicyDecision
import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode
import com.jarvis.core.verification.DefaultResultVerifier
import com.jarvis.core.verification.ExpectedEffects
import com.jarvis.core.verification.ObservedState
import com.jarvis.core.verification.ResultVerifier
import com.jarvis.core.verification.VerificationRequest
import com.jarvis.core.verification.VerificationResult
import com.jarvis.core.verification.VerificationStatus

/**
 * V2-01: единая точка исполнения live actions.
 *
 * ```
 * ToolCall + ToolDefinition
 *   → LiveSkillRiskBridge (RiskBasedPolicy semantics)
 *   → AutonomyActionPipeline (RiskClassifier → Policy → SafetyGate)
 *   → ToolExecutor
 *   → ResultVerifier (soft / boundary)
 * ```
 *
 * Не обходит SafetyGate. Не смешивает роли в один god-class — композиция.
 */
class ExecutionOrchestrator(
    private val toolExecutor: ToolExecutor,
    private val pipeline: AutonomyActionPipeline = AutonomyActionPipeline(),
    private val bridge: LiveSkillRiskBridge = LiveSkillRiskBridge(),
    private val verifier: ResultVerifier = DefaultResultVerifier(),
    private val riskClassifier: ActionRiskClassifier = ActionRiskClassifier(),
    /**
     * Пользовательская настройка «подтверждать действия уровня CONFIRM»
     * (`JarvisSettings.confirmSensitive`, по умолчанию true).
     *
     * core не зависит от Android: значение приходит лямбдой из app-слоя.
     * false ослабляет подтверждение **только** для [RiskLevel.CONFIRM];
     * SENSITIVE/BLOCKED и фактический риск выше MEDIUM не затрагиваются.
     */
    private val confirmLevelRequired: () -> Boolean = { true },
) {

    sealed class Outcome {
        data class Executed(
            val toolResult: ToolResult,
            val verification: VerificationResult,
            val autonomyStatus: AutonomyPlanStatus,
        ) : Outcome()

        data class NeedsConfirmation(
            val reason: String,
            val integrityFingerprint: String?,
            val autonomyStatus: AutonomyPlanStatus,
        ) : Outcome()

        data class Denied(
            val reason: String,
            val autonomyStatus: AutonomyPlanStatus?,
        ) : Outcome()

        data class Failed(
            val reason: String,
            val autonomyStatus: AutonomyPlanStatus? = null,
        ) : Outcome()
    }

    /**
     * @param userConfirmed true после голосового «да» (pending confirm).
     * @param priorFingerprint fingerprint, сохранённый при первом NeedsConfirmation.
     */
    fun executeAction(
        toolCall: ToolCall,
        definition: ToolDefinition,
        requestId: String = toolCall.requestId,
        userConfirmed: Boolean = false,
        priorFingerprint: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        autonomyLevel: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
    ): Outcome {
        val bridged = bridge.bridge(toolCall, definition)

        // confirm_sensitive=false снимает диалог ровно с уровня CONFIRM и только пока
        // фактический риск не выше MEDIUM. Всё, что опаснее, идёт обычным путём.
        val confirmBypass = definition.riskLevel == RiskLevel.CONFIRM &&
            !confirmLevelRequired() &&
            riskClassifier.classify(bridged.proposed).riskClass.ordinal <= ActionRiskClass.MEDIUM.ordinal
        val legacyDecision =
            if (confirmBypass && bridged.legacy is PolicyDecision.RequiresConfirmation) {
                PolicyDecision.Allow
            } else {
                bridged.legacy
            }

        // Legacy BLOCKED / DENY — fail closed до pipeline (и логируем classify).
        when (val legacy = legacyDecision) {
            is PolicyDecision.Deny -> {
                riskClassifier.classify(bridged.proposed) // side-effect free; ensures risk path exists
                return Outcome.Denied(
                    reason = legacy.reason,
                    autonomyStatus = AutonomyPlanStatus.DENIED,
                )
            }
            is PolicyDecision.RequiresConfirmation -> {
                if (!userConfirmed) {
                    val assessment = riskClassifier.classify(bridged.proposed)
                    val fingerprint = ActionIntegrity.fingerprint(bridged.proposed, assessment)
                    // Полный pipeline → AWAITING_CONFIRMATION (без exec).
                    val plan = pipeline.plan(
                        decision = userUtteranceDecision(toolCall.toolId, nowMillis),
                        proposed = bridged.proposed,
                        level = autonomyLevel,
                        allowlist = bridged.allowlist,
                        nowMillis = nowMillis,
                        requestId = requestId,
                        source = DecisionSource.USER_UTTERANCE,
                        allowedArgumentKeys = allowedKeys(bridged.proposed.arguments),
                        grantedPermissions = emptySet(),
                        requiredPermissionsOverride = emptySet(),
                    )
                    return Outcome.NeedsConfirmation(
                        reason = legacy.reason,
                        integrityFingerprint = fingerprint,
                        autonomyStatus = plan.status,
                    )
                }
                // userConfirmed: продолжаем через pipeline + confirmation binding
            }
            is PolicyDecision.Allow -> Unit
        }

        val proposedBase = bridged.proposed
        // После голосового «да» (pending UX) маппим в Allow-эквивалент kind:
        // ActionConfirmation token требует стабильный ActionRequest.id, который
        // pipeline создаёт заново на каждый plan() — conversational confirm
        // остаётся на Orchestrator; Gate всё равно проходит Risk→Policy→PASS.
        val proposed = if (userConfirmed || confirmBypass) {
            proposedBase.copy(
                kind = when {
                    proposedBase.toolId in LiveSkillRiskBridge.READ_TOOL_IDS ||
                        proposedBase.kind == com.jarvis.core.autonomy.ActionKind.READ_ONLY ->
                        com.jarvis.core.autonomy.ActionKind.READ_ONLY
                    else -> com.jarvis.core.autonomy.ActionKind.LOCAL_DEVICE_SAFE
                },
            )
        } else {
            proposedBase
        }
        val assessment = riskClassifier.classify(proposed)
        val fingerprint = priorFingerprint
            ?: ActionIntegrity.fingerprint(proposed, assessment)

        val catalogRequired = com.jarvis.core.autonomy.ActionArgumentRules
            .requiredPermissionsFor(proposed)
        val requiredOverride = when (proposed.kind) {
            com.jarvis.core.autonomy.ActionKind.NONE,
            com.jarvis.core.autonomy.ActionKind.READ_ONLY,
            com.jarvis.core.autonomy.ActionKind.LOCAL_DEVICE_SAFE,
            -> emptySet()
            else -> catalogRequired.ifEmpty { setOf("capability:LIVE_USER") }
        }
        val granted = if (userConfirmed || legacyDecision is PolicyDecision.Allow) {
            requiredOverride
        } else {
            emptySet()
        }
        val allowlist = if (userConfirmed || confirmBypass) emptySet() else bridged.allowlist

        val plan = pipeline.plan(
            decision = userUtteranceDecision(toolCall.toolId, nowMillis),
            proposed = proposed,
            level = autonomyLevel,
            allowlist = allowlist,
            nowMillis = nowMillis,
            requestId = requestId,
            grantedPermissions = granted,
            confirmation = null,
            source = DecisionSource.USER_UTTERANCE,
            allowedArgumentKeys = allowedKeys(proposed.arguments),
            requiredPermissionsOverride = requiredOverride,
        )
        // fingerprint retained for trace / future token binding
        @Suppress("UNUSED_VARIABLE")
        val traceFingerprint = fingerprint

        return when (plan.status) {
            AutonomyPlanStatus.READY_TO_EXECUTE -> {
                val call = (plan.draftToolCall ?: toolCall).copy(requestId = requestId)
                // Preserve original argument richness when draft truncated; only if same tool.
                val execCall = if (call.toolId == toolCall.toolId) {
                    toolCall.copy(requestId = requestId)
                } else {
                    call
                }
                val result = toolExecutor.execute(execCall)
                val verification = softVerify(execCall, result, requestId)
                Outcome.Executed(
                    toolResult = result,
                    verification = verification,
                    autonomyStatus = plan.status,
                )
            }
            AutonomyPlanStatus.AWAITING_CONFIRMATION,
            AutonomyPlanStatus.NEEDS_CONFIRMATION,
            -> Outcome.NeedsConfirmation(
                reason = plan.verdict.reason.ifBlank { "Confirmation required" },
                integrityFingerprint = fingerprint,
                autonomyStatus = plan.status,
            )
            AutonomyPlanStatus.DENIED, AutonomyPlanStatus.NO_ACTION -> Outcome.Denied(
                reason = plan.verdict.reason.ifBlank { "Denied by autonomy pipeline" },
                autonomyStatus = plan.status,
            )
        }
    }

    private fun softVerify(
        call: ToolCall,
        result: ToolResult,
        requestId: String,
    ): VerificationResult {
        val args = LiveSkillRiskBridge.stringifyArgs(call.arguments)
        val facts = result.data.entries
            .take(16)
            .associate { (k, v) -> k.take(64) to v.toString().take(256) }
            .toMutableMap()
        // Normalize common tool payloads into ExpectedEffects keys when possible.
        when (call.toolId) {
            "flashlight" -> {
                val on = facts["on"]?.lowercase()
                if (on == "true" || on == "on") facts.putIfAbsent("flashlight", "on")
                if (on == "false" || on == "off") facts.putIfAbsent("flashlight", "off")
                facts["action"]?.let { a ->
                    if (a == "on" || a == "off") facts.putIfAbsent("flashlight", a)
                }
            }
        }
        val observed = if (facts.isEmpty()) {
            null
        } else {
            ObservedState(capturedAtMillis = System.currentTimeMillis(), facts = facts)
        }
        val expectedAll = ExpectedEffects.forTool(call.toolId, args)
        val expected = expectedAll.filter { e ->
            observed?.facts?.containsKey(e.key) == true
        }
        return verifier.verify(
            VerificationRequest(
                stepId = "$requestId/verify",
                toolId = call.toolId,
                toolResultStatus = result.status.name,
                toolResultMessage = result.message,
                toolResultData = facts,
                expectedEffects = expected,
                observed = observed,
            ),
        )
    }

    private fun userUtteranceDecision(toolId: String, nowMillis: Long) = ProactiveDecision(
        type = ProactiveDecisionType.SUGGESTION,
        behaviorPatternKey = "live.skill:$toolId",
        reasonCode = ProactiveReasonCode.SELECTED,
        reason = "V2-01 live skill execution",
        confidence = 0.95f,
        relevance = 0.95f,
        createdAtMillis = nowMillis,
    )

    private fun allowedKeys(args: Map<String, String>): Set<String> =
        args.keys + setOf("action", "query", "city", "text", "app", "package", "name")
}

/** Convenience: verification status string for traces. */
fun VerificationResult.traceLabel(): String =
    when (status) {
        VerificationStatus.SUCCESS -> "SUCCESS"
        VerificationStatus.PARTIAL_SUCCESS -> "PARTIAL"
        VerificationStatus.FAILURE -> "FAILURE"
        VerificationStatus.UNKNOWN -> "UNKNOWN"
        VerificationStatus.RETRYABLE -> "RETRYABLE"
        VerificationStatus.IRREVERSIBLE -> "IRREVERSIBLE"
    }
