package com.jarvis.core.autonomy

/**
 * Stage 8B: immutable результат [ActionRiskClassifier].
 * risk ≠ permission: assessment никогда не означает ALLOW/DENY.
 */
data class ActionRiskAssessment(
    val riskClass: ActionRiskClass,
    val reasonCode: ActionRiskReasonCode,
    val reason: String = "",
    val factors: List<String> = emptyList(),
    val actionId: String = "",
    val kind: ActionKind = ActionKind.UNKNOWN,
)
