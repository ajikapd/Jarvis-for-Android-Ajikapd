package com.jarvis.core.autonomy

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.proactive.ProactiveDecision
import java.util.UUID

/**
 * Stage 8E helper: план после policy+gate. Ещё не execution.
 */
enum class AutonomyPlanStatus {
    NO_ACTION,
    DENIED,
    NEEDS_CONFIRMATION,
    /** Gate BLOCK из-за отсутствия/невалидного confirmation. */
    AWAITING_CONFIRMATION,
    /** SafetyGate PASS — готово к Executor (Executor вне этого stage). */
    READY_TO_EXECUTE,
}

data class AutonomyActionPlan(
    val status: AutonomyPlanStatus,
    val verdict: AutonomyVerdict,
    val proposedAction: ProposedAction?,
    val gateResult: ActionSafetyGateResult? = null,
    val draftToolCall: ToolCall? = null,
    val notes: List<String> = emptyList(),
)

/**
 * decision → RiskClassifier → AutonomyPolicy → ActionSafetyGate → plan.
 * Не вызывает ToolExecutor.
 */
class AutonomyActionPipeline(
    private val policy: AutonomyPolicy = DefaultAutonomyPolicy(),
    private val gate: ActionSafetyGate = DefaultActionSafetyGate(),
    private val riskClassifier: ActionRiskClassifier = ActionRiskClassifier(),
) {

    fun plan(
        decision: ProactiveDecision,
        proposed: ProposedAction?,
        level: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
        allowlist: Set<String> = emptySet(),
        blocklist: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
        requestId: String = UUID.randomUUID().toString(),
        grantedPermissions: Set<String> = emptySet(),
        confirmation: ActionConfirmation? = null,
        contextCapturedAtMillis: Long = nowMillis,
        /** V2-01: USER_UTTERANCE for live skills; default PROACTIVE for agent/companion. */
        source: DecisionSource = DecisionSource.PROACTIVE,
        /**
         * V2-01 live skills: allow keys present on the ToolCall (still subject to
         * [ActionArgumentRules.GLOBAL_FORBIDDEN_KEYS]). null → catalog rules only.
         */
        allowedArgumentKeys: Set<String>? = null,
        /**
         * When non-null, replaces [ActionArgumentRules.requiredPermissionsFor] for Gate.
         * Live user-utterance Allow path may supply declared tool requirements as granted.
         */
        requiredPermissionsOverride: Set<String>? = null,
    ): AutonomyActionPlan {
        val assessment = proposed?.let { riskClassifier.classify(it) }
        val policyVerdict = policy.evaluate(
            AutonomyPolicyInput(
                decision = decision,
                proposedAction = proposed,
                autonomyLevel = level,
                riskAssessment = assessment,
                source = source,
                userAllowlistActionIds = allowlist,
                userBlocklistActionIds = blocklist,
                nowMillis = nowMillis,
            ),
        )
        AutonomyAuditLog.record(policyVerdict, stage = "pipeline.policy")

        val binding = if (proposed != null && assessment != null) {
            ActionIntegrity.binding(proposed, assessment)
        } else {
            null
        }
        val required = requiredPermissionsOverride
            ?: proposed?.let { ActionArgumentRules.requiredPermissionsFor(it) }.orEmpty()
        val gateResult = gate.evaluate(
            ActionSafetyGateInput(
                verdict = policyVerdict,
                riskAssessment = assessment,
                nowMillis = nowMillis,
                requiredPermissions = required,
                grantedPermissions = grantedPermissions,
                contextFreshness = ContextFreshness(
                    contextCapturedAtMillis = contextCapturedAtMillis,
                ),
                decisionValidity = DecisionValidity(decidedAtMillis = nowMillis),
                confirmation = confirmation,
                policyBinding = binding,
                presentedAction = proposed,
                allowedArgumentKeys = allowedArgumentKeys,
            ),
        )
        AutonomyAuditLog.record(gateResult.verdict, stage = "pipeline.gate")

        val draft = if (gateResult.passed) {
            proposed?.let { toDraftToolCall(it, requestId) }
        } else {
            null
        }

        val status = when {
            policyVerdict.type == AutonomyVerdictType.NO_ACTION -> AutonomyPlanStatus.NO_ACTION
            !gateResult.passed &&
                gateResult.failureReason in setOf(
                    ActionSafetyFailureReason.MISSING_CONFIRMATION,
                    ActionSafetyFailureReason.EXPIRED_CONFIRMATION,
                    ActionSafetyFailureReason.INVALID_CONFIRMATION,
                    ActionSafetyFailureReason.CONFIRMATION_ACTION_MISMATCH,
                    ActionSafetyFailureReason.CONFIRMATION_ALREADY_USED,
                ) -> AutonomyPlanStatus.AWAITING_CONFIRMATION
            !gateResult.passed -> AutonomyPlanStatus.DENIED
            gateResult.passed -> AutonomyPlanStatus.READY_TO_EXECUTE
            else -> AutonomyPlanStatus.DENIED
        }

        return AutonomyActionPlan(
            status = status,
            verdict = gateResult.verdict,
            proposedAction = proposed,
            gateResult = gateResult,
            draftToolCall = draft,
            notes = gateResult.notes + "plan=$status",
        )
    }

    private fun toDraftToolCall(proposed: ProposedAction, requestId: String): ToolCall? {
        val toolId = proposed.toolId ?: return null
        return ToolCall(
            requestId = requestId,
            toolId = toolId,
            arguments = proposed.arguments.mapValues { it.value as Any },
        )
    }
}
