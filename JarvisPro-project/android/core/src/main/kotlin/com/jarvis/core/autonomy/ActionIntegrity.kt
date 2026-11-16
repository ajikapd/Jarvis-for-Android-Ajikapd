package com.jarvis.core.autonomy

/**
 * Stage 8D: детерминированный fingerprint security-relevant полей.
 * Без crypto-зависимостей; каноническая строка достаточна для binding equality.
 */
object ActionIntegrity {

    fun fingerprint(action: ProposedAction, assessment: ActionRiskAssessment): String {
        val args = action.arguments.toSortedMap().entries.joinToString(";") { (k, v) ->
            "$k=$v"
        }
        return listOf(
            "v1",
            action.actionId,
            action.kind.name,
            action.toolId.orEmpty(),
            args,
            assessment.actionId,
            assessment.riskClass.name,
            assessment.kind.name,
            assessment.reasonCode.name,
        ).joinToString("|")
    }

    fun binding(action: ProposedAction, assessment: ActionRiskAssessment): PolicyDecisionBinding =
        PolicyDecisionBinding(
            integrityFingerprint = fingerprint(action, assessment),
            policyAction = action,
            policyRiskAssessment = assessment,
        )
}
