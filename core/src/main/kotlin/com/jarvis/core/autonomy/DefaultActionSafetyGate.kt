package com.jarvis.core.autonomy

/**
 * Stage 8D: детерминированный fail-closed ActionSafetyGate.
 *
 * Не вызывает: LLM, сеть, RiskClassifier, AutonomyPolicy, ToolExecutor, Android APIs.
 * Не ослабляет DENY / NO_ACTION. Не повышает autonomy. Не выдаёт permissions.
 *
 * Проверки: arguments, permissions, context freshness, confirmation,
 * decision TTL, plan integrity, forbidden parameters.
 */
class DefaultActionSafetyGate(
    private val clockSkewToleranceMillis: Long = CLOCK_SKEW_TOLERANCE_MS,
) : ActionSafetyGate {

    override fun evaluate(input: ActionSafetyGateInput): ActionSafetyGateResult {
        val verdict = input.verdict
        val notesBase = verdict.notes + "gate=DefaultActionSafetyGate"

        // 1) Non-executable verdicts never PASS
        when (verdict.type) {
            AutonomyVerdictType.DENY ->
                return block(
                    verdict,
                    ActionSafetyFailureReason.DENY_VERDICT,
                    "Policy DENY cannot pass SafetyGate",
                    notesBase,
                )
            AutonomyVerdictType.NO_ACTION ->
                return block(
                    verdict,
                    ActionSafetyFailureReason.NO_ACTION_VERDICT,
                    "NO_ACTION is not executable",
                    notesBase,
                )
            AutonomyVerdictType.ALLOW,
            AutonomyVerdictType.REQUIRE_CONFIRMATION,
            -> Unit
        }

        val req = verdict.actionRequest
            ?: return block(
                verdict.copy(
                    type = AutonomyVerdictType.DENY,
                    reasonCode = AutonomyReasonCode.SAFETY_GATE_DENY,
                    reason = "Missing actionRequest",
                ),
                ActionSafetyFailureReason.MISSING_ACTION_REQUEST,
                "ALLOW/CONFIRM without actionRequest",
                notesBase,
            )

        val presented = input.presentedAction ?: req.proposedAction
        val assessment = input.riskAssessment
            ?: return block(
                harden(verdict, "Missing risk assessment"),
                ActionSafetyFailureReason.MISSING_RISK_ASSESSMENT,
                "Fail-closed: riskAssessment required",
                notesBase,
            )

        if (assessment.actionId.isNotBlank() &&
            assessment.actionId != presented.actionId
        ) {
            return block(
                harden(verdict, "Risk assessment mismatch"),
                ActionSafetyFailureReason.RISK_ASSESSMENT_MISMATCH,
                "assessment.actionId != presented.actionId",
                notesBase,
            )
        }

        // 2) Plan integrity / immutable binding
        val binding = input.policyBinding
            ?: return block(
                harden(verdict, "Missing policy binding"),
                ActionSafetyFailureReason.MISSING_POLICY_BINDING,
                "Fail-closed: policyBinding required",
                notesBase,
            )

        val integrityFail = checkIntegrity(presented, assessment, binding)
        if (integrityFail != null) {
            return block(
                harden(verdict, integrityFail.second),
                integrityFail.first,
                integrityFail.second,
                notesBase,
            )
        }

        // 3) Forbidden / unknown / invalid arguments
        val allowed = input.allowedArgumentKeys
            ?: ActionArgumentRules.allowedKeysFor(presented)
        val argFail = ActionArgumentRules.validateArguments(
            action = presented,
            allowedKeys = allowed,
            forbiddenKeys = input.forbiddenArgumentKeys,
        )
        if (argFail != null) {
            return block(
                harden(verdict, "Argument validation failed: $argFail"),
                argFail,
                "Argument validation failed: $argFail",
                notesBase,
            )
        }

        // 4) Permissions (declare vs granted — Gate does not request)
        val required = if (input.requiredPermissions.isNotEmpty()) {
            input.requiredPermissions
        } else {
            ActionArgumentRules.requiredPermissionsFor(presented)
        }
        // High-risk kinds must have explicit requirements (catalog or input)
        if (presented.kind in KINDS_REQUIRING_EXPLICIT_PERMS && required.isEmpty()) {
            return block(
                harden(verdict, "Permissions unspecified for kind"),
                ActionSafetyFailureReason.PERMISSIONS_UNSPECIFIED,
                "Fail-closed: ${presented.kind} requires declared permissions",
                notesBase,
            )
        }
        if (!input.grantedPermissions.containsAll(required)) {
            return block(
                harden(verdict, "Missing permissions"),
                ActionSafetyFailureReason.MISSING_PERMISSION,
                "Missing permissions: ${(required - input.grantedPermissions).joinToString()}",
                notesBase,
            )
        }

        // 5) Context freshness
        val freshness = input.contextFreshness
            ?: return block(
                harden(verdict, "Missing context freshness"),
                ActionSafetyFailureReason.MISSING_CONTEXT_FRESHNESS,
                "Fail-closed: contextFreshness required",
                notesBase,
            )
        val freshnessTs = checkTimestamp(freshness.contextCapturedAtMillis, input.nowMillis)
        if (freshnessTs != null) {
            return block(harden(verdict, freshnessTs.second), freshnessTs.first, freshnessTs.second, notesBase)
        }
        val age = input.nowMillis - freshness.contextCapturedAtMillis
        if (age > freshness.maxAgeMillis) {
            return block(
                harden(verdict, "Stale context"),
                ActionSafetyFailureReason.STALE_CONTEXT,
                "Context age=${age}ms > max=${freshness.maxAgeMillis}ms",
                notesBase,
            )
        }

        // 6) Decision TTL
        val validity = input.decisionValidity
            ?: return block(
                harden(verdict, "Missing decision validity"),
                ActionSafetyFailureReason.MISSING_DECISION_VALIDITY,
                "Fail-closed: decisionValidity required",
                notesBase,
            )
        val decidedTs = checkTimestamp(validity.decidedAtMillis, input.nowMillis)
        if (decidedTs != null) {
            return block(harden(verdict, decidedTs.second), decidedTs.first, decidedTs.second, notesBase)
        }
        if (validity.ttlMillis < 0L) {
            return block(
                harden(verdict, "Malformed decision TTL"),
                ActionSafetyFailureReason.MALFORMED_TIMESTAMP,
                "ttlMillis must be >= 0",
                notesBase,
            )
        }
        val expiresAt = validity.decidedAtMillis + validity.ttlMillis
        if (input.nowMillis > expiresAt) {
            return block(
                harden(verdict, "Decision expired"),
                ActionSafetyFailureReason.EXPIRED_DECISION,
                "Decision expired at=$expiresAt now=${input.nowMillis}",
                notesBase,
            )
        }

        // 7) Confirmation state
        when (verdict.type) {
            AutonomyVerdictType.REQUIRE_CONFIRMATION -> {
                val conf = input.confirmation
                    ?: return block(
                        verdict,
                        ActionSafetyFailureReason.MISSING_CONFIRMATION,
                        "REQUIRE_CONFIRMATION without confirmation",
                        notesBase,
                    )
                val confFail = checkConfirmation(conf, req, binding, input.nowMillis)
                if (confFail != null) {
                    return block(verdict, confFail.first, confFail.second, notesBase)
                }
            }
            AutonomyVerdictType.ALLOW -> {
                // ALLOW must not require artificial confirmation.
                // If a confirmation is supplied, it must still bind correctly (no elevation tricks),
                // but absence is fine.
                input.confirmation?.let { conf ->
                    val confFail = checkConfirmation(conf, req, binding, input.nowMillis)
                    if (confFail != null) {
                        return block(verdict, confFail.first, confFail.second, notesBase)
                    }
                }
            }
            else -> Unit
        }

        return ActionSafetyGateResult(
            status = ActionSafetyGateStatus.PASS,
            failureReason = null,
            reason = "SafetyGate PASS",
            verdict = verdict.copy(notes = notesBase + "gate=pass"),
            notes = notesBase + "gate=pass",
        )
    }

    private fun checkIntegrity(
        presented: ProposedAction,
        assessment: ActionRiskAssessment,
        binding: PolicyDecisionBinding,
    ): Pair<ActionSafetyFailureReason, String>? {
        val expected = binding.integrityFingerprint
        val actual = ActionIntegrity.fingerprint(presented, assessment)
        if (actual != expected) {
            // Narrower reasons when possible
            if (presented.kind != binding.policyAction.kind) {
                return ActionSafetyFailureReason.ACTION_KIND_MODIFIED to
                    "ActionKind changed after Policy"
            }
            if (presented.toolId != binding.policyAction.toolId ||
                presented.actionId != binding.policyAction.actionId
            ) {
                return ActionSafetyFailureReason.TARGET_MODIFIED to
                    "Target/actionId/toolId changed after Policy"
            }
            if (presented.arguments != binding.policyAction.arguments) {
                return ActionSafetyFailureReason.ARGUMENTS_MODIFIED to
                    "Arguments changed after Policy"
            }
            if (assessment.riskClass != binding.policyRiskAssessment.riskClass ||
                assessment.kind != binding.policyRiskAssessment.kind ||
                assessment.actionId != binding.policyRiskAssessment.actionId
            ) {
                return ActionSafetyFailureReason.RISK_ASSESSMENT_MISMATCH to
                    "Risk assessment changed after Policy"
            }
            return ActionSafetyFailureReason.PLAN_INTEGRITY_FAILURE to
                "Integrity fingerprint mismatch"
        }
        // Also require binding snapshot equality for assessment identity
        if (assessment.riskClass != binding.policyRiskAssessment.riskClass) {
            return ActionSafetyFailureReason.RISK_ASSESSMENT_MISMATCH to
                "riskClass differs from policy binding"
        }
        return null
    }

    private fun checkConfirmation(
        conf: ActionConfirmation,
        req: ActionRequest,
        binding: PolicyDecisionBinding,
        nowMillis: Long,
    ): Pair<ActionSafetyFailureReason, String>? {
        if (conf.confirmationId.isBlank()) {
            return ActionSafetyFailureReason.INVALID_CONFIRMATION to "Blank confirmationId"
        }
        if (conf.consumed) {
            return ActionSafetyFailureReason.CONFIRMATION_ALREADY_USED to "Confirmation already used"
        }
        if (conf.boundActionRequestId != req.id) {
            return ActionSafetyFailureReason.CONFIRMATION_ACTION_MISMATCH to
                "Confirmation bound to different ActionRequest"
        }
        if (conf.boundIntegrityFingerprint != binding.integrityFingerprint) {
            return ActionSafetyFailureReason.CONFIRMATION_ACTION_MISMATCH to
                "Confirmation fingerprint mismatch"
        }
        if (conf.confirmedAtMillis <= 0L || conf.expiresAtMillis <= 0L) {
            return ActionSafetyFailureReason.MALFORMED_TIMESTAMP to
                "Malformed confirmation timestamps"
        }
        if (conf.expiresAtMillis < conf.confirmedAtMillis) {
            return ActionSafetyFailureReason.MALFORMED_TIMESTAMP to
                "expiresAt < confirmedAt"
        }
        if (nowMillis + clockSkewToleranceMillis < conf.confirmedAtMillis) {
            return ActionSafetyFailureReason.CLOCK_ANOMALY to
                "Confirmation timestamp in the future"
        }
        if (nowMillis > conf.expiresAtMillis) {
            return ActionSafetyFailureReason.EXPIRED_CONFIRMATION to
                "Confirmation expired"
        }
        return null
    }

    private fun checkTimestamp(
        eventMillis: Long,
        nowMillis: Long,
    ): Pair<ActionSafetyFailureReason, String>? {
        if (eventMillis <= 0L || nowMillis <= 0L) {
            return ActionSafetyFailureReason.MALFORMED_TIMESTAMP to
                "Timestamp must be > 0"
        }
        if (nowMillis + clockSkewToleranceMillis < eventMillis) {
            return ActionSafetyFailureReason.CLOCK_ANOMALY to
                "Event timestamp in the future (clock anomaly)"
        }
        return null
    }

    private fun harden(verdict: AutonomyVerdict, reason: String): AutonomyVerdict {
        if (verdict.type == AutonomyVerdictType.DENY ||
            verdict.type == AutonomyVerdictType.NO_ACTION
        ) {
            return verdict
        }
        return verdict.copy(
            type = AutonomyVerdictType.DENY,
            reasonCode = AutonomyReasonCode.SAFETY_GATE_DENY,
            reason = reason,
            notes = verdict.notes + "gate_hardened=true",
        )
    }

    private fun block(
        verdict: AutonomyVerdict,
        failure: ActionSafetyFailureReason,
        reason: String,
        notes: List<String>,
    ) = ActionSafetyGateResult(
        status = ActionSafetyGateStatus.BLOCK,
        failureReason = failure,
        reason = reason,
        verdict = verdict,
        notes = notes + "gate=block:$failure",
    )

    companion object {
        const val CLOCK_SKEW_TOLERANCE_MS: Long = 2_000L

        private val KINDS_REQUIRING_EXPLICIT_PERMS = setOf(
            ActionKind.COMMUNICATION,
            ActionKind.UI_AUTOMATION,
            ActionKind.DATA_DELETION,
            ActionKind.FINANCIAL,
            ActionKind.SYSTEM_SETTINGS,
            ActionKind.UNKNOWN,
        )
    }
}
