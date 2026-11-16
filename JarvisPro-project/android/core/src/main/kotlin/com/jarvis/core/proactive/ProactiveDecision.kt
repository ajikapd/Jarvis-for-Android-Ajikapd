package com.jarvis.core.proactive

/**
 * Immutable result Stage 7 — decision only, без execution / ToolCall / Intent.
 */
data class ProactiveDecision(
    val type: ProactiveDecisionType,
    val behaviorPatternKey: String? = null,
    val behaviorId: String? = null,
    val fingerprint: String? = null,
    val reasonCode: ProactiveReasonCode,
    val reason: String = "",
    val confidence: Float = 0f,
    val relevance: Float = 0f,
    val createdAtMillis: Long = 0L,
    val cooldownUntilMillis: Long? = null,
    val notes: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

/** Результат evaluate: decision + явное следующее состояние (без скрытых side effects). */
data class DecisionEvaluationResult(
    val decision: ProactiveDecision,
    val nextState: ProactivityState,
)
