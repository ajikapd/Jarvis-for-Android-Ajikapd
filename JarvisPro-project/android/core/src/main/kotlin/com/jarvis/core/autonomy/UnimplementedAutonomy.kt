package com.jarvis.core.autonomy

/**
 * Stage 8A: заглушка-политика — всегда NO_ACTION / NOT_IMPLEMENTED.
 * Нужна, чтобы контракты были тестируемы без Stage 8C logic.
 * Не исполняет действий.
 */
object UnimplementedAutonomyPolicy : AutonomyPolicy {
    override fun evaluate(input: AutonomyPolicyInput): AutonomyVerdict {
        if (input.decision.type == com.jarvis.core.proactive.ProactiveDecisionType.NO_OP) {
            return ActionRequestFactory.noAction(
                AutonomyReasonCode.PROACTIVE_NO_OP,
                "Proactive NO_OP — no action",
            )
        }
        if (input.proposedAction == null) {
            return ActionRequestFactory.noAction(
                AutonomyReasonCode.NO_PROPOSED_ACTION,
                "No proposed action mapped",
            )
        }
        if (input.autonomyLevel == AutonomyLevel.OFF) {
            return AutonomyVerdict(
                type = AutonomyVerdictType.DENY,
                reasonCode = AutonomyReasonCode.AUTONOMY_LEVEL_OFF,
                reason = "Autonomy is OFF",
                actionRequest = ActionRequestFactory.fromProactive(
                    input.decision,
                    input.proposedAction,
                    input.source,
                    input.nowMillis,
                ),
            )
        }
        // Stage 8A: намеренно не Allow — даже при high confidence
        return AutonomyVerdict(
            type = AutonomyVerdictType.REQUIRE_CONFIRMATION,
            reasonCode = AutonomyReasonCode.NOT_IMPLEMENTED,
            reason = "AutonomyPolicy not implemented (Stage 8C). Confidence alone never ALLOWs.",
            actionRequest = ActionRequestFactory.fromProactive(
                input.decision,
                input.proposedAction,
                input.source,
                input.nowMillis,
            ),
            notes = listOf(
                "conf=${input.decision.confidence}",
                "level=${input.autonomyLevel}",
                "risk_kind=${input.proposedAction.kind}",
            ),
        )
    }
}

/**
 * Stage 8A stub gate — блокирует любой executable path до реальной 8D logic в тестах контрактов.
 * Не ослабляет DENY / NO_ACTION.
 */
object UnimplementedActionSafetyGate : ActionSafetyGate {
    override fun evaluate(input: ActionSafetyGateInput): ActionSafetyGateResult {
        val v = input.verdict
        return when (v.type) {
            AutonomyVerdictType.DENY -> ActionSafetyGateResult(
                status = ActionSafetyGateStatus.BLOCK,
                failureReason = ActionSafetyFailureReason.DENY_VERDICT,
                reason = "DENY cannot pass",
                verdict = v,
                notes = v.notes + "gate=unimplemented",
            )
            AutonomyVerdictType.NO_ACTION -> ActionSafetyGateResult(
                status = ActionSafetyGateStatus.BLOCK,
                failureReason = ActionSafetyFailureReason.NO_ACTION_VERDICT,
                reason = "NO_ACTION cannot pass",
                verdict = v,
                notes = v.notes + "gate=unimplemented",
            )
            AutonomyVerdictType.ALLOW, AutonomyVerdictType.REQUIRE_CONFIRMATION -> {
                val hardened = v.copy(
                    type = AutonomyVerdictType.DENY,
                    reasonCode = AutonomyReasonCode.SAFETY_GATE_DENY,
                    reason = "ActionSafetyGate stub blocks executable path",
                    notes = v.notes + "gate=unimplemented",
                )
                ActionSafetyGateResult(
                    status = ActionSafetyGateStatus.BLOCK,
                    failureReason = ActionSafetyFailureReason.UNKNOWN_SECURITY_STATE,
                    reason = "UnimplementedActionSafetyGate blocks all executable verdicts",
                    verdict = hardened,
                    notes = hardened.notes,
                )
            }
        }
    }
}
