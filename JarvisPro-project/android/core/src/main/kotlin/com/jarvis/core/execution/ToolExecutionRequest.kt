package com.jarvis.core.execution

import com.jarvis.core.autonomy.ActionRiskAssessment
import com.jarvis.core.autonomy.ActionSafetyGateStatus
import com.jarvis.core.autonomy.AutonomyVerdictType
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import java.util.UUID

/**
 * Stage 8E: immutable request к будущему Executor.
 *
 * Создаётся **только** через [ToolExecutionRequestFactory] после SafetyGate PASS.
 * Не пересматривает Policy/Gate. Не выполняет действие.
 */
data class ToolExecutionRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolId: String,
    val toolDefinition: ToolDefinition,
    val input: ToolInput,
    val proposedAction: ProposedAction,
    val integrityFingerprint: String,
    val riskAssessment: ActionRiskAssessment,
    val policyVerdictType: AutonomyVerdictType,
    val gateStatus: ActionSafetyGateStatus,
    val actionRequestId: String,
    val metadata: ToolMetadata = ToolMetadata(),
    val createdAtMillis: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "request id blank" }
        require(toolId.isNotBlank()) { "toolId blank" }
        require(toolId == toolDefinition.id) { "toolId != toolDefinition.id" }
        require(integrityFingerprint.isNotBlank()) { "integrityFingerprint blank" }
        require(actionRequestId.isNotBlank()) { "actionRequestId blank" }
        require(gateStatus == ActionSafetyGateStatus.PASS) {
            "ToolExecutionRequest requires gate PASS"
        }
        require(
            policyVerdictType == AutonomyVerdictType.ALLOW ||
                policyVerdictType == AutonomyVerdictType.REQUIRE_CONFIRMATION,
        ) {
            "ToolExecutionRequest forbids DENY/NO_ACTION verdict"
        }
        require(proposedAction.toolId == null || proposedAction.toolId == toolId) {
            "proposedAction.toolId mismatch"
        }
        require(input.matches(proposedAction)) {
            "ToolInput must match ProposedAction.arguments"
        }
    }

    /** Legacy bridge для существующего ToolExecutor (не вызывает его). */
    fun toLegacyToolCall(): ToolCall = ToolCall(
        requestId = id,
        toolId = toolId,
        arguments = input.parameters.mapValues { it.value as Any },
    )
}

/**
 * Причины отказа создать [ToolExecutionRequest].
 */
enum class ToolExecutionRequestRejectReason {
    GATE_BLOCKED,
    DENY_VERDICT,
    NO_ACTION_VERDICT,
    MISSING_ACTION_REQUEST,
    MISSING_BINDING,
    MISSING_RISK_ASSESSMENT,
    INVALID_TOOL_DEFINITION,
    TOOL_MISMATCH,
    INPUT_MISMATCH,
    ACTION_TAMPERED,
    INVALID_INPUT,
    FORBIDDEN_PARAMETER,
    UNKNOWN_PARAMETER,
    RISK_MISMATCH,
}

sealed class ToolExecutionRequestCreateResult {
    data class Created(val request: ToolExecutionRequest) : ToolExecutionRequestCreateResult()

    data class Rejected(
        val reason: ToolExecutionRequestRejectReason,
        val message: String,
    ) : ToolExecutionRequestCreateResult()

    val isCreated: Boolean get() = this is Created
}
