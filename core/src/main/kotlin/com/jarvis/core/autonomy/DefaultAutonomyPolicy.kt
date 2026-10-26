package com.jarvis.core.autonomy

import com.jarvis.core.proactive.ProactiveDecisionType

/**
 * Stage 8C: детерминированная AutonomyPolicy.
 *
 * Вход: ProposedAction + ActionRiskAssessment + AutonomyLevel + DecisionSource + allow/blocklist.
 * Выход: NO_ACTION / REQUIRE_CONFIRMATION / ALLOW / DENY.
 *
 * Архитектурные инварианты:
 * - **не** вызывает [ActionRiskClassifier] (risk уже посчитан на 8B);
 * - **не** вызывает ToolExecutor / Android API / сеть / LLM;
 * - side-effect free / fail-closed;
 * - confidence **сам по себе** никогда не повышает вердикт до ALLOW для HIGH/CRITICAL.
 *
 * Policy matrix (user/manual/proactive; SYSTEM — см. ниже):
 * ```
 * Level\Risk   NONE     LOW      MEDIUM           HIGH              CRITICAL
 * OFF          DENY     DENY     DENY             DENY              DENY
 * CONFIRM_ALL  ALLOW    CONFIRM  CONFIRM          DENY*             DENY*
 * SAFE_ONLY    ALLOW    ALLOW    CONFIRM          DENY*             DENY*
 * ASSISTED     ALLOW    ALLOW    CONFIRM          DENY*             DENY*
 * ```
 * \* HIGH/CRITICAL + allowlist → REQUIRE_CONFIRMATION (никогда silent ALLOW).
 * SYSTEM source: risk > NONE → минимум REQUIRE_CONFIRMATION (нет silent ALLOW).
 */
class DefaultAutonomyPolicy : AutonomyPolicy {

    override fun evaluate(input: AutonomyPolicyInput): AutonomyVerdict {
        if (input.decision.type == ProactiveDecisionType.NO_OP) {
            return ActionRequestFactory.noAction(
                AutonomyReasonCode.PROACTIVE_NO_OP,
                "Proactive NO_OP",
            )
        }
        val proposed = input.proposedAction
        if (proposed == null || proposed.kind == ActionKind.NONE) {
            return ActionRequestFactory.noAction(
                AutonomyReasonCode.NO_PROPOSED_ACTION,
                "No proposed action",
            )
        }

        val actionRequest = ActionRequestFactory.fromProactive(
            decision = input.decision,
            proposedAction = proposed,
            source = input.source,
            nowMillis = input.nowMillis,
        ) ?: return ActionRequestFactory.noAction(
            AutonomyReasonCode.NO_PROPOSED_ACTION,
            "ActionRequestFactory rejected",
        )

        val assessment = input.riskAssessment
            ?: return deny(
                actionRequest,
                AutonomyReasonCode.MISSING_RISK_ASSESSMENT,
                "Fail-closed: ActionRiskAssessment required",
                listOf("src=${input.source}", "action=${proposed.actionId}"),
            )

        if (assessment.actionId.isNotBlank() &&
            assessment.actionId != proposed.actionId
        ) {
            return deny(
                actionRequest,
                AutonomyReasonCode.RISK_ASSESSMENT_MISMATCH,
                "Fail-closed: assessment.actionId != proposed.actionId",
                listOf(
                    "proposed=${proposed.actionId}",
                    "assessed=${assessment.actionId}",
                ),
            )
        }

        if (input.autonomyLevel == AutonomyLevel.OFF) {
            return AutonomyVerdict(
                type = AutonomyVerdictType.DENY,
                reasonCode = AutonomyReasonCode.AUTONOMY_LEVEL_OFF,
                reason = "Autonomy is OFF",
                actionRequest = actionRequest,
            )
        }

        val actionId = proposed.actionId
        if (actionId in input.userBlocklistActionIds) {
            return AutonomyVerdict(
                type = AutonomyVerdictType.DENY,
                reasonCode = AutonomyReasonCode.USER_BLOCKLISTED,
                reason = "Action blocklisted by user: $actionId",
                actionRequest = actionRequest,
            )
        }

        val risk = assessment.riskClass
        val allowlisted = actionId in input.userAllowlistActionIds
        val notes = listOf(
            "risk=${risk.name}",
            "kind=${assessment.kind}",
            "riskReason=${assessment.reasonCode}",
            "level=${input.autonomyLevel}",
            "conf=${input.decision.confidence}",
            "src=${input.source}",
            "allowlisted=$allowlisted",
        ) + assessment.factors.take(3)

        // CRITICAL: deny, or confirm if explicitly allowlisted (never silent ALLOW)
        if (risk == ActionRiskClass.CRITICAL) {
            return if (allowlisted) {
                confirm(
                    actionRequest,
                    AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                    "CRITICAL allowlisted — confirmation required (never silent ALLOW)",
                    notes + "conf_irrelevant=true",
                )
            } else {
                deny(
                    actionRequest,
                    AutonomyReasonCode.RISK_CLASS_TOO_HIGH,
                    "CRITICAL risk denied",
                    notes,
                )
            }
        }

        // HIGH: deny unless allowlisted → confirm only
        if (risk == ActionRiskClass.HIGH) {
            return if (allowlisted) {
                confirm(
                    actionRequest,
                    AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                    "HIGH allowlisted — confirmation required",
                    notes + "conf_irrelevant=true",
                )
            } else {
                deny(
                    actionRequest,
                    AutonomyReasonCode.RISK_CLASS_TOO_HIGH,
                    "HIGH risk denied (not allowlisted)",
                    notes,
                )
            }
        }

        // SYSTEM: never silent ALLOW for mutating / non-NONE risk
        if (input.source == DecisionSource.SYSTEM && risk != ActionRiskClass.NONE) {
            return confirm(
                actionRequest,
                AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                "SYSTEM source requires confirmation for risk=${risk.name}",
                notes + "system_no_silent_allow=true",
            )
        }

        when (input.autonomyLevel) {
            AutonomyLevel.OFF -> error("handled above")

            AutonomyLevel.CONFIRM_ALL -> {
                if (risk == ActionRiskClass.NONE) {
                    return allow(actionRequest, notes + "read_only_ok")
                }
                return confirm(
                    actionRequest,
                    AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                    "CONFIRM_ALL level",
                    notes,
                )
            }

            AutonomyLevel.SAFE_ONLY -> {
                return when (risk) {
                    ActionRiskClass.NONE, ActionRiskClass.LOW ->
                        allow(actionRequest, notes)
                    ActionRiskClass.MEDIUM ->
                        confirm(
                            actionRequest,
                            AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                            "SAFE_ONLY: MEDIUM needs confirmation",
                            notes,
                        )
                    else ->
                        deny(
                            actionRequest,
                            AutonomyReasonCode.RISK_CLASS_TOO_HIGH,
                            "SAFE_ONLY rejects ${risk.name}",
                            notes,
                        )
                }
            }

            AutonomyLevel.ASSISTED -> {
                if (input.decision.type == ProactiveDecisionType.CONFIRMATION_REQUEST &&
                    risk.ordinal >= ActionRiskClass.MEDIUM.ordinal
                ) {
                    return confirm(
                        actionRequest,
                        AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                        "Decision asked for confirmation",
                        notes,
                    )
                }
                return when (risk) {
                    ActionRiskClass.NONE, ActionRiskClass.LOW ->
                        allow(actionRequest, notes)
                    ActionRiskClass.MEDIUM ->
                        confirm(
                            actionRequest,
                            AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
                            "ASSISTED: MEDIUM needs confirmation",
                            notes,
                        )
                    else ->
                        deny(
                            actionRequest,
                            AutonomyReasonCode.RISK_CLASS_TOO_HIGH,
                            "ASSISTED rejects ${risk.name}",
                            notes,
                        )
                }
            }
        }
    }

    private fun allow(req: ActionRequest, notes: List<String>) = AutonomyVerdict(
        type = AutonomyVerdictType.ALLOW,
        reasonCode = AutonomyReasonCode.POLICY_ALLOW,
        reason = "Allowed by DefaultAutonomyPolicy",
        actionRequest = req,
        notes = notes,
    )

    private fun confirm(
        req: ActionRequest,
        code: AutonomyReasonCode,
        reason: String,
        notes: List<String>,
    ) = AutonomyVerdict(
        type = AutonomyVerdictType.REQUIRE_CONFIRMATION,
        reasonCode = code,
        reason = reason,
        actionRequest = req,
        notes = notes,
    )

    private fun deny(
        req: ActionRequest,
        code: AutonomyReasonCode,
        reason: String,
        notes: List<String>,
    ) = AutonomyVerdict(
        type = AutonomyVerdictType.DENY,
        reasonCode = code,
        reason = reason,
        actionRequest = req,
        notes = notes,
    )
}
