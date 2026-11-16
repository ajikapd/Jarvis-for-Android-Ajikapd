package com.jarvis.core.execution

import com.jarvis.core.autonomy.ActionIntegrity
import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.autonomy.ActionRiskAssessment
import com.jarvis.core.autonomy.ActionRiskClass
import com.jarvis.core.autonomy.ActionRiskReasonCode
import com.jarvis.core.autonomy.ActionSafetyFailureReason
import com.jarvis.core.autonomy.ActionSafetyGateResult
import com.jarvis.core.autonomy.ActionSafetyGateStatus
import com.jarvis.core.autonomy.ActionRequest
import com.jarvis.core.autonomy.AutonomyReasonCode
import com.jarvis.core.autonomy.AutonomyVerdict
import com.jarvis.core.autonomy.AutonomyVerdictType
import com.jarvis.core.autonomy.DecisionSource
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolExecutionContractsTest {

    private val now = 1_000_000L

    private fun flashlightAction(
        args: Map<String, String> = mapOf("action" to "on"),
    ) = ProposedAction(
        actionId = "flashlight",
        kind = ActionKind.LOCAL_DEVICE_SAFE,
        displaySummary = "Фонарик",
        toolId = "flashlight",
        arguments = args,
    )

    private fun assessment(action: ProposedAction, risk: ActionRiskClass = ActionRiskClass.LOW) =
        ActionRiskAssessment(
            riskClass = risk,
            reasonCode = if (risk == ActionRiskClass.NONE) {
                ActionRiskReasonCode.READ_ONLY
            } else {
                ActionRiskReasonCode.LOCAL_REVERSIBLE
            },
            reason = "t",
            factors = listOf("t"),
            actionId = action.actionId,
            kind = action.kind,
        )

    private fun flashlightDefinition() = ToolDefinition(
        id = "flashlight",
        name = "Фонарик",
        description = "Torch control",
        parameters = listOf(
            ToolParameter(
                name = "action",
                description = "on|off|toggle",
                type = ParameterType.ENUM,
                required = true,
                allowedValues = listOf("on", "off", "toggle"),
            ),
        ),
    )

    private fun passGate(
        action: ProposedAction,
        type: AutonomyVerdictType = AutonomyVerdictType.ALLOW,
        reqId: String = "req-1",
    ): ActionSafetyGateResult {
        val verdict = AutonomyVerdict(
            type = type,
            reasonCode = if (type == AutonomyVerdictType.ALLOW) {
                AutonomyReasonCode.POLICY_ALLOW
            } else {
                AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION
            },
            reason = "ok",
            actionRequest = ActionRequest(
                id = reqId,
                source = DecisionSource.USER_UTTERANCE,
                proposedAction = action,
                createdAtMillis = now,
            ),
            notes = listOf("gate=pass"),
        )
        return ActionSafetyGateResult(
            status = ActionSafetyGateStatus.PASS,
            failureReason = null,
            reason = "PASS",
            verdict = verdict,
            notes = listOf("gate=pass"),
        )
    }

    private fun createOk(
        action: ProposedAction = flashlightAction(),
        definition: ToolDefinition = flashlightDefinition(),
        input: ToolInput? = null,
        presented: ProposedAction? = null,
        gate: ActionSafetyGateResult? = null,
        bindingAction: ProposedAction? = null,
        assessmentOverride: ActionRiskAssessment? = null,
    ): ToolExecutionRequestCreateResult {
        val a = bindingAction ?: action
        val assess = assessmentOverride ?: assessment(a)
        val binding = ActionIntegrity.binding(a, assess)
        return ToolExecutionRequestFactory.create(
            gateResult = gate ?: passGate(action),
            toolDefinition = definition,
            policyBinding = binding,
            riskAssessment = assess,
            input = input,
            presentedAction = presented ?: action,
            nowMillis = now,
        )
    }

    // --- ToolDefinition ---

    @Test
    fun toolDefinitionValidStableId() {
        val d = flashlightDefinition()
        assertEquals("flashlight", d.id)
        assertEquals(ToolDefinition.CONTRACT_VERSION_8E, d.contractVersion)
    }

    @Test
    fun toolDefinitionRejectsBlankId() {
        try {
            ToolDefinition(id = " ", name = "x", description = "d")
            fail("expected require")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun toolDefinitionMetadataCannotOverrideSecurity_viaToolMetadata() {
        try {
            ToolMetadata(extras = mapOf("force_allow" to "true"))
            fail("expected require")
        } catch (_: IllegalArgumentException) {
        }
    }

    // --- ToolInput ---

    @Test
    fun toolInputValidArgs() {
        val input = ToolInput(mapOf("action" to "on"))
        assertEquals("on", input.parameters["action"])
        assertTrue(input.matches(flashlightAction()))
    }

    @Test
    fun toolInputRejectsOversizedValue() {
        try {
            ToolInput(mapOf("action" to "x".repeat(ProposedAction.MAX_VALUE + 1)))
            fail("expected require")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun toolInputMissingRequiredDetectedByFactory() {
        val action = flashlightAction(args = emptyMap())
        // ProposedAction allows empty args; factory validates against ToolDefinition.required
        // But ActionArgumentRules for flashlight also requires action → INVALID_INPUT
        val r = createOk(action = action, bindingAction = action)
        assertTrue(r is ToolExecutionRequestCreateResult.Rejected)
        val rejected = r as ToolExecutionRequestCreateResult.Rejected
        assertTrue(
            rejected.reason == ToolExecutionRequestRejectReason.INVALID_INPUT,
        )
    }

    @Test
    fun toolInputForbiddenParameterRejected() {
        // Can't construct ProposedAction with forbidden key easily if we use factory path
        // with mismatched input:
        val action = flashlightAction()
        val badInput = ToolInput(mapOf("action" to "on", "token" to "x"))
        val r = createOk(action = action, input = badInput)
        assertTrue(r is ToolExecutionRequestCreateResult.Rejected)
        assertEquals(
            ToolExecutionRequestRejectReason.INPUT_MISMATCH,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun toolInputUnknownSecurityParamViaTamperedActionRejected() {
        val original = flashlightAction()
        val tampered = original.copy(arguments = mapOf("action" to "on", "extra_hack" to "1"))
        val r = createOk(action = original, presented = tampered, bindingAction = original)
        assertTrue(r is ToolExecutionRequestCreateResult.Rejected)
        assertEquals(
            ToolExecutionRequestRejectReason.ACTION_TAMPERED,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    // --- ToolExecutionRequest ---

    @Test
    fun createRequestAfterPass() {
        val r = createOk()
        assertTrue(r is ToolExecutionRequestCreateResult.Created)
        val req = (r as ToolExecutionRequestCreateResult.Created).request
        assertEquals("flashlight", req.toolId)
        assertEquals(ActionSafetyGateStatus.PASS, req.gateStatus)
        assertEquals(AutonomyVerdictType.ALLOW, req.policyVerdictType)
        assertEquals("on", req.input.parameters["action"])
        assertEquals(req.id, req.toLegacyToolCall().requestId)
    }

    @Test
    fun createRequestPreservesBindingFingerprint() {
        val action = flashlightAction()
        val assess = assessment(action)
        val binding = ActionIntegrity.binding(action, assess)
        val r = ToolExecutionRequestFactory.create(
            gateResult = passGate(action),
            toolDefinition = flashlightDefinition(),
            policyBinding = binding,
            riskAssessment = assess,
            presentedAction = action,
            nowMillis = now,
        ) as ToolExecutionRequestCreateResult.Created
        assertEquals(binding.integrityFingerprint, r.request.integrityFingerprint)
    }

    @Test
    fun missingBindingRejected() {
        val action = flashlightAction()
        val r = ToolExecutionRequestFactory.create(
            gateResult = passGate(action),
            toolDefinition = flashlightDefinition(),
            policyBinding = null,
            riskAssessment = assessment(action),
            presentedAction = action,
        )
        assertEquals(
            ToolExecutionRequestRejectReason.MISSING_BINDING,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun incompatibleToolRejected() {
        val action = flashlightAction()
        val r = createOk(
            action = action,
            definition = ToolDefinition(id = "volume", name = "Vol", description = "v"),
        )
        assertEquals(
            ToolExecutionRequestRejectReason.TOOL_MISMATCH,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun incompatibleArgumentsRejected() {
        val action = flashlightAction()
        val r = createOk(
            action = action,
            input = ToolInput(mapOf("action" to "off")),
        )
        assertEquals(
            ToolExecutionRequestRejectReason.INPUT_MISMATCH,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun actionChangedAfterBindingRejected() {
        val original = flashlightAction(mapOf("action" to "on"))
        val changed = flashlightAction(mapOf("action" to "off"))
        val r = createOk(action = original, presented = changed, bindingAction = original)
        assertEquals(
            ToolExecutionRequestRejectReason.ACTION_TAMPERED,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun toolChangedAfterBindingRejected() {
        val original = flashlightAction()
        val changed = original.copy(toolId = "volume")
        val r = createOk(action = original, presented = changed, bindingAction = original)
        assertEquals(
            ToolExecutionRequestRejectReason.ACTION_TAMPERED,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun inputChangedAfterBindingRejected() {
        val action = flashlightAction()
        val r = createOk(action = action, input = ToolInput(mapOf("action" to "toggle")))
        assertEquals(
            ToolExecutionRequestRejectReason.INPUT_MISMATCH,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun denyCannotBecomeRequest() {
        val deny = AutonomyVerdict(
            type = AutonomyVerdictType.DENY,
            reasonCode = AutonomyReasonCode.POLICY_DENY,
            reason = "no",
        )
        val gate = ActionSafetyGateResult(
            status = ActionSafetyGateStatus.BLOCK,
            failureReason = ActionSafetyFailureReason.DENY_VERDICT,
            reason = "deny",
            verdict = deny,
        )
        val action = flashlightAction()
        val assess = assessment(action)
        val r = ToolExecutionRequestFactory.create(
            gateResult = gate,
            toolDefinition = flashlightDefinition(),
            policyBinding = ActionIntegrity.binding(action, assess),
            riskAssessment = assess,
            presentedAction = action,
        )
        assertEquals(
            ToolExecutionRequestRejectReason.GATE_BLOCKED,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun noActionCannotBecomeRequest() {
        val no = AutonomyVerdict(
            type = AutonomyVerdictType.NO_ACTION,
            reasonCode = AutonomyReasonCode.PROACTIVE_NO_OP,
        )
        // Constructing PASS with NO_ACTION is forbidden by ActionSafetyGateResult init.
        // Factory still must reject NO_ACTION if somehow presented with BLOCK:
        val gate = ActionSafetyGateResult(
            status = ActionSafetyGateStatus.BLOCK,
            failureReason = ActionSafetyFailureReason.NO_ACTION_VERDICT,
            reason = "no",
            verdict = no,
        )
        val action = flashlightAction()
        val assess = assessment(action)
        val r = ToolExecutionRequestFactory.create(
            gateResult = gate,
            toolDefinition = flashlightDefinition(),
            policyBinding = ActionIntegrity.binding(action, assess),
            riskAssessment = assess,
        )
        assertEquals(
            ToolExecutionRequestRejectReason.GATE_BLOCKED,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    @Test
    fun blockCannotBecomeRequest() {
        val action = flashlightAction()
        val allow = AutonomyVerdict(
            type = AutonomyVerdictType.ALLOW,
            reasonCode = AutonomyReasonCode.POLICY_ALLOW,
            actionRequest = ActionRequest(
                id = "r",
                source = DecisionSource.USER_UTTERANCE,
                proposedAction = action,
            ),
        )
        val gate = ActionSafetyGateResult(
            status = ActionSafetyGateStatus.BLOCK,
            failureReason = ActionSafetyFailureReason.STALE_CONTEXT,
            reason = "stale",
            verdict = allow.copy(
                type = AutonomyVerdictType.DENY,
                reasonCode = AutonomyReasonCode.SAFETY_GATE_DENY,
            ),
        )
        val assess = assessment(action)
        val r = ToolExecutionRequestFactory.create(
            gateResult = gate,
            toolDefinition = flashlightDefinition(),
            policyBinding = ActionIntegrity.binding(action, assess),
            riskAssessment = assess,
            presentedAction = action,
        )
        assertEquals(
            ToolExecutionRequestRejectReason.GATE_BLOCKED,
            (r as ToolExecutionRequestCreateResult.Rejected).reason,
        )
    }

    // --- ToolExecutionResult / ToolError ---

    @Test
    fun executionResultSuccess() {
        val res = ToolExecutionResult.success(
            requestId = "req",
            toolId = "flashlight",
            output = mapOf("on" to "true"),
        )
        assertEquals(ToolExecutionStatus.SUCCESS, res.status)
        assertNull(res.error)
        assertEquals("true", res.output["on"])
        assertEquals("req", res.requestId)
    }

    @Test
    fun executionResultFailureKeepsError() {
        val err = ToolError.executionFailure("boom")
        val res = ToolExecutionResult.failure("req", "flashlight", err)
        assertEquals(ToolExecutionStatus.FAILURE, res.status)
        assertNotNull(res.error)
        assertEquals(ToolErrorCategory.EXECUTION_FAILURE, res.error!!.category)
    }

    @Test
    fun successCannotCarryError() {
        try {
            ToolExecutionResult(
                requestId = "r",
                toolId = "t",
                status = ToolExecutionStatus.SUCCESS,
                error = ToolError.internal("x"),
            )
            fail("expected require")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun failureRequiresError() {
        try {
            ToolExecutionResult(
                requestId = "r",
                toolId = "t",
                status = ToolExecutionStatus.FAILURE,
                error = null,
            )
            fail("expected require")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun allErrorCategoriesRepresentable() {
        val all = listOf(
            ToolError.invalidInput("i"),
            ToolError.unavailable("u"),
            ToolError.permissionFailure("p"),
            ToolError.executionFailure("e"),
            ToolError.timeout(),
            ToolError.cancelled(),
            ToolError.unsupported(),
            ToolError.internal("x"),
        )
        assertEquals(ToolErrorCategory.values().size, all.size)
        all.forEach { err ->
            val res = ToolExecutionResult.failure("r", "t", err)
            assertEquals(ToolExecutionStatus.FAILURE, res.status)
            assertFalse(res.status == ToolExecutionStatus.SUCCESS)
        }
    }

    @Test
    fun unimplementedExecutorNeverSucceeds() {
        val created = createOk() as ToolExecutionRequestCreateResult.Created
        val res = UnimplementedFutureToolExecutor.execute(created.request)
        assertEquals(ToolExecutionStatus.FAILURE, res.status)
        assertEquals(ToolErrorCategory.UNSUPPORTED, res.error!!.category)
        assertEquals(created.request.id, res.requestId)
    }

    @Test
    fun confirmPassCanCreateRequest() {
        val action = flashlightAction()
        val r = createOk(action = action, gate = passGate(action, AutonomyVerdictType.REQUIRE_CONFIRMATION))
        assertTrue(r.isCreated)
        assertEquals(
            AutonomyVerdictType.REQUIRE_CONFIRMATION,
            (r as ToolExecutionRequestCreateResult.Created).request.policyVerdictType,
        )
    }
}
