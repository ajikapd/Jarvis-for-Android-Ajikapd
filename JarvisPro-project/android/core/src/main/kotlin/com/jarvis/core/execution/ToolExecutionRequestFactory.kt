package com.jarvis.core.execution

import com.jarvis.core.autonomy.ActionArgumentRules
import com.jarvis.core.autonomy.ActionIntegrity
import com.jarvis.core.autonomy.ActionRiskAssessment
import com.jarvis.core.autonomy.ActionSafetyFailureReason
import com.jarvis.core.autonomy.ActionSafetyGateResult
import com.jarvis.core.autonomy.ActionSafetyGateStatus
import com.jarvis.core.autonomy.AutonomyVerdictType
import com.jarvis.core.autonomy.PolicyDecisionBinding
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.common.model.ToolDefinition

/**
 * Stage 8E: фабрика execution request.
 *
 * Единственная точка создания [ToolExecutionRequest].
 * Не решает autonomy заново — только проверяет, что Gate PASS и binding цел.
 * Не вызывает Android / ToolExecutor.
 */
object ToolExecutionRequestFactory {

    fun create(
        gateResult: ActionSafetyGateResult,
        toolDefinition: ToolDefinition,
        policyBinding: PolicyDecisionBinding?,
        riskAssessment: ActionRiskAssessment?,
        input: ToolInput? = null,
        presentedAction: ProposedAction? = null,
        metadata: ToolMetadata = ToolMetadata(),
        nowMillis: Long = 0L,
    ): ToolExecutionRequestCreateResult {
        if (gateResult.status != ActionSafetyGateStatus.PASS) {
            return reject(
                ToolExecutionRequestRejectReason.GATE_BLOCKED,
                "SafetyGate ${gateResult.status}: ${gateResult.failureReason ?: "?"}",
            )
        }

        val verdict = gateResult.verdict
        when (verdict.type) {
            AutonomyVerdictType.DENY ->
                return reject(
                    ToolExecutionRequestRejectReason.DENY_VERDICT,
                    "DENY cannot become ToolExecutionRequest",
                )
            AutonomyVerdictType.NO_ACTION ->
                return reject(
                    ToolExecutionRequestRejectReason.NO_ACTION_VERDICT,
                    "NO_ACTION cannot become ToolExecutionRequest",
                )
            AutonomyVerdictType.ALLOW,
            AutonomyVerdictType.REQUIRE_CONFIRMATION,
            -> Unit
        }

        val actionRequest = verdict.actionRequest
            ?: return reject(
                ToolExecutionRequestRejectReason.MISSING_ACTION_REQUEST,
                "Missing actionRequest on PASS verdict",
            )

        val binding = policyBinding
            ?: return reject(
                ToolExecutionRequestRejectReason.MISSING_BINDING,
                "policyBinding required",
            )

        val assessment = riskAssessment
            ?: return reject(
                ToolExecutionRequestRejectReason.MISSING_RISK_ASSESSMENT,
                "riskAssessment required",
            )

        if (toolDefinition.id.isBlank() || toolDefinition.name.isBlank()) {
            return reject(
                ToolExecutionRequestRejectReason.INVALID_TOOL_DEFINITION,
                "ToolDefinition id/name required",
            )
        }

        val action = presentedAction ?: actionRequest.proposedAction

        val resolvedInput = input ?: ToolInput.fromProposedAction(action)
        if (!resolvedInput.matches(action)) {
            return reject(
                ToolExecutionRequestRejectReason.INPUT_MISMATCH,
                "ToolInput does not match ProposedAction.arguments",
            )
        }
        if (action.arguments != binding.policyAction.arguments ||
            action.kind != binding.policyAction.kind ||
            action.actionId != binding.policyAction.actionId ||
            action.toolId != binding.policyAction.toolId
        ) {
            return reject(
                ToolExecutionRequestRejectReason.ACTION_TAMPERED,
                "Presented action differs from policy binding snapshot",
            )
        }

        if (assessment.riskClass != binding.policyRiskAssessment.riskClass ||
            assessment.kind != binding.policyRiskAssessment.kind ||
            assessment.actionId != binding.policyRiskAssessment.actionId
        ) {
            return reject(
                ToolExecutionRequestRejectReason.RISK_MISMATCH,
                "Risk assessment differs from policy binding",
            )
        }

        val fingerprint = ActionIntegrity.fingerprint(action, assessment)
        if (fingerprint != binding.integrityFingerprint) {
            return reject(
                ToolExecutionRequestRejectReason.ACTION_TAMPERED,
                "Integrity fingerprint mismatch after SafetyGate",
            )
        }

        // Tool must match proposed action target (after integrity)
        val expectedToolId = action.toolId
        if (expectedToolId != null && expectedToolId != toolDefinition.id) {
            return reject(
                ToolExecutionRequestRejectReason.TOOL_MISMATCH,
                "toolDefinition.id=$toolDefinition.id != proposed.toolId=$expectedToolId",
            )
        }
        if (expectedToolId == null) {
            return reject(
                ToolExecutionRequestRejectReason.TOOL_MISMATCH,
                "ProposedAction.toolId required for execution request",
            )
        }

        // Reuse 8D argument rules (does not re-decide autonomy)
        when (val argFail = ActionArgumentRules.validateArguments(
            action = action,
            allowedKeys = ActionArgumentRules.allowedKeysFor(action),
            forbiddenKeys = ActionArgumentRules.GLOBAL_FORBIDDEN_KEYS,
        )) {
            ActionSafetyFailureReason.FORBIDDEN_PARAMETER ->
                return reject(
                    ToolExecutionRequestRejectReason.FORBIDDEN_PARAMETER,
                    "Forbidden parameter in ToolInput",
                )
            ActionSafetyFailureReason.UNKNOWN_PARAMETER ->
                return reject(
                    ToolExecutionRequestRejectReason.UNKNOWN_PARAMETER,
                    "Unknown parameter in ToolInput",
                )
            ActionSafetyFailureReason.INVALID_ARGUMENTS ->
                return reject(
                    ToolExecutionRequestRejectReason.INVALID_INPUT,
                    "Invalid ToolInput arguments",
                )
            null -> Unit
            else ->
                return reject(
                    ToolExecutionRequestRejectReason.INVALID_INPUT,
                    "Argument validation failed: $argFail",
                )
        }

        // Validate against ToolDefinition required parameters if declared
        for (param in toolDefinition.parameters.filter { it.required }) {
            if (!resolvedInput.parameters.containsKey(param.name)) {
                return reject(
                    ToolExecutionRequestRejectReason.INVALID_INPUT,
                    "Missing required parameter: ${param.name}",
                )
            }
            if (param.allowedValues.isNotEmpty()) {
                val v = resolvedInput.parameters[param.name]
                if (v !in param.allowedValues) {
                    return reject(
                        ToolExecutionRequestRejectReason.INVALID_INPUT,
                        "Parameter ${param.name}=$v not in allowedValues",
                    )
                }
            }
        }

        val request = ToolExecutionRequest(
            toolId = toolDefinition.id,
            toolDefinition = toolDefinition,
            input = resolvedInput,
            proposedAction = action,
            integrityFingerprint = fingerprint,
            riskAssessment = assessment,
            policyVerdictType = verdict.type,
            gateStatus = ActionSafetyGateStatus.PASS,
            actionRequestId = actionRequest.id,
            metadata = metadata,
            createdAtMillis = nowMillis,
        )
        return ToolExecutionRequestCreateResult.Created(request)
    }

    private fun reject(
        reason: ToolExecutionRequestRejectReason,
        message: String,
    ) = ToolExecutionRequestCreateResult.Rejected(reason, message)
}
