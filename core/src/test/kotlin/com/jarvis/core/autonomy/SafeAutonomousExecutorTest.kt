package com.jarvis.core.autonomy

import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeAutonomousExecutorTest {

    private fun decision() = ProactiveDecision(
        type = ProactiveDecisionType.SUGGESTION,
        reasonCode = ProactiveReasonCode.SELECTED,
        confidence = 0.9f,
        relevance = 0.9f,
        createdAtMillis = 1_000_000L,
        fingerprint = "fp",
        behaviorPatternKey = "p",
        reason = "test",
    )

    private fun fakeFlashlight(): Tool = object : Tool {
        override val definition = ToolDefinition(
            id = "flashlight",
            name = "Flashlight",
            description = "test",
            riskLevel = RiskLevel.SAFE,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        )
        override fun execute(call: ToolCall): ToolResult = ToolResult(
            requestId = call.requestId,
            toolId = "flashlight",
            status = ToolResultStatus.SUCCESS,
            message = "ok",
            data = mapOf("on" to true),
        )
    }

    @Test
    fun executesFlashlightWhenGateAllows() {
        val tools = ToolRegistry().apply { register(fakeFlashlight()) }
        val exec = SafeAutonomousExecutor(
            toolExecutor = DefaultToolExecutor(tools),
            pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()),
        )
        val proposed = ProposedActionMapper.fromToolId(
            "flashlight",
            args = mapOf("action" to "on"),
        )!!
        val r = exec.tryExecute(decision(), proposed, AutonomyLevel.SAFE_ONLY, nowMillis = 1_000_000L)
        assertTrue(r.executed)
        assertEquals(ToolResultStatus.SUCCESS, r.result!!.status)
    }

    @Test
    fun autonomyOffDoesNotExecute() {
        val tools = ToolRegistry().apply { register(fakeFlashlight()) }
        val exec = SafeAutonomousExecutor(
            toolExecutor = DefaultToolExecutor(tools),
            pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()),
        )
        val proposed = ProposedActionMapper.fromToolId(
            "flashlight",
            args = mapOf("action" to "on"),
        )!!
        val r = exec.tryExecute(decision(), proposed, AutonomyLevel.OFF, nowMillis = 1_000_000L)
        assertFalse(r.executed)
        assertEquals(AutonomyPlanStatus.DENIED, r.plan!!.status)
    }

    @Test
    fun refusesSmsEvenIfMapped() {
        val tools = ToolRegistry()
        val exec = SafeAutonomousExecutor(
            toolExecutor = DefaultToolExecutor(tools),
            pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()),
        )
        val proposed = ProposedAction(
            actionId = "sms_send",
            kind = ActionKind.COMMUNICATION,
            displaySummary = "SMS",
            toolId = "sms",
        )
        val r = exec.tryExecute(decision(), proposed)
        assertFalse(r.executed)
        assertTrue(r.reason.contains("FIRST_SAFE"))
    }
}
