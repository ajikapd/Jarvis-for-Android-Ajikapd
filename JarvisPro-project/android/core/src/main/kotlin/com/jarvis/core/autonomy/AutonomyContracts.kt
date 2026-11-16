package com.jarvis.core.autonomy

import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType

/**
 * Stage 8A contract: политика автономности.
 * Реализация — [DefaultAutonomyPolicy] (Stage 8C).
 *
 * Не вызывает ToolRegistry / ToolExecutor / ActionRiskClassifier.
 */
fun interface AutonomyPolicy {
    fun evaluate(input: AutonomyPolicyInput): AutonomyVerdict
}

/**
 * Stage 8A/8D contract: финальный safety gate перед Executor.
 * Реализация — [DefaultActionSafetyGate].
 *
 * Может только ужесточить (BLOCK / harden ALLOW→DENY), не ослаблять DENY.
 * Не вызывает RiskClassifier / ToolExecutor / Android API.
 */
fun interface ActionSafetyGate {
    fun evaluate(input: ActionSafetyGateInput): ActionSafetyGateResult
}

/**
 * Stage 8A: сборка [ActionRequest] из proactive decision + proposed action.
 * Не мапит behavior→tool (это позже). Не исполняет.
 */
object ActionRequestFactory {

    /**
     * @return null если decision NO_OP / нет proposed action /
     *         decision не предполагает действие (QUESTION без mapped action — caller решает).
     */
    fun fromProactive(
        decision: ProactiveDecision,
        proposedAction: ProposedAction?,
        source: DecisionSource = DecisionSource.PROACTIVE,
        nowMillis: Long = decision.createdAtMillis,
    ): ActionRequest? {
        if (decision.type == ProactiveDecisionType.NO_OP) return null
        if (proposedAction == null) return null
        if (proposedAction.kind == ActionKind.NONE) return null

        return ActionRequest(
            source = source,
            proposedAction = proposedAction,
            behaviorPatternKey = decision.behaviorPatternKey,
            proactiveDecisionType = decision.type,
            confidence = decision.confidence,
            relevance = decision.relevance,
            fingerprint = decision.fingerprint,
            createdAtMillis = nowMillis,
            notes = listOf(
                "conf=${decision.confidence}",
                "rel=${decision.relevance}",
                "dtype=${decision.type}",
            ),
        )
    }

    /** Явный NO_ACTION вердикт без side effects. */
    fun noAction(
        reasonCode: AutonomyReasonCode,
        reason: String = "",
        notes: List<String> = emptyList(),
    ): AutonomyVerdict = AutonomyVerdict(
        type = AutonomyVerdictType.NO_ACTION,
        reasonCode = reasonCode,
        reason = reason,
        actionRequest = null,
        notes = notes,
    )
}
