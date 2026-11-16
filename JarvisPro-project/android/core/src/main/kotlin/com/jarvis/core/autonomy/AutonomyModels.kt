package com.jarvis.core.autonomy

import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import java.util.UUID

/**
 * Stage 8A: запрос на действие после proactive decision.
 * Ещё не ToolCall — intermediate contract перед SafetyGate / Executor.
 */
data class ActionRequest(
    val id: String = UUID.randomUUID().toString(),
    val source: DecisionSource,
    val proposedAction: ProposedAction,
    val behaviorPatternKey: String? = null,
    val proactiveDecisionType: ProactiveDecisionType? = null,
    val confidence: Float = 0f,
    val relevance: Float = 0f,
    val fingerprint: String? = null,
    val createdAtMillis: Long = 0L,
    val notes: List<String> = emptyList(),
)

/**
 * Stage 8A: explainable вердикт. Immutable.
 *
 * Инвариант: [type] == ALLOW ⇒ [actionRequest] != null.
 * Инвариант: high [ActionRequest.confidence] сам по себе не создаёт ALLOW.
 */
data class AutonomyVerdict(
    val type: AutonomyVerdictType,
    val reasonCode: AutonomyReasonCode,
    val reason: String = "",
    val actionRequest: ActionRequest? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        if (type == AutonomyVerdictType.ALLOW) {
            require(actionRequest != null) { "ALLOW requires actionRequest" }
        }
        if (type == AutonomyVerdictType.NO_ACTION) {
            require(actionRequest == null) { "NO_ACTION must not carry actionRequest" }
        }
    }
}

/**
 * Вход AutonomyPolicy (Stage 8C).
 *
 * [riskAssessment] приходит **снаружи** (от Stage 8B ActionRiskClassifier).
 * Policy не вызывает classifier — разделение Risk vs Permission.
 */
data class AutonomyPolicyInput(
    val decision: ProactiveDecision,
    val proposedAction: ProposedAction?,
    val autonomyLevel: AutonomyLevel,
    val riskAssessment: ActionRiskAssessment? = null,
    val source: DecisionSource = DecisionSource.PROACTIVE,
    val userAllowlistActionIds: Set<String> = emptySet(),
    val userBlocklistActionIds: Set<String> = emptySet(),
    val nowMillis: Long = 0L,
)
