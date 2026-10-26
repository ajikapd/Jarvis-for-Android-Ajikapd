package com.jarvis.core.runtime

import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.autonomy.AutonomyPlanStatus
import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionOrchestratorTest {

    private class FlashTool : Tool {
        override val definition = ToolDefinition(
            id = "flashlight",
            name = "Фонарик",
            description = "t",
            parameters = listOf(
                ToolParameter("action", "on/off", ParameterType.ENUM, true, listOf("on", "off")),
            ),
            riskLevel = RiskLevel.SAFE,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        )

        override fun execute(call: ToolCall): ToolResult =
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "ok")
    }

    private class BlockedTool : Tool {
        override val definition = ToolDefinition(
            id = "blocked_demo",
            name = "Blocked",
            description = "t",
            riskLevel = RiskLevel.BLOCKED,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        )

        override fun execute(call: ToolCall): ToolResult =
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "should not run")
    }

    private class ConfirmTool : Tool {
        override val definition = ToolDefinition(
            id = "phone.call",
            name = "Звонок",
            description = "t",
            parameters = listOf(
                ToolParameter("contact", "name", ParameterType.STRING, true),
            ),
            riskLevel = RiskLevel.CONFIRM,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        )

        @Volatile var executed = false
        override fun execute(call: ToolCall): ToolResult {
            executed = true
            return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "calling")
        }
    }

    @Test
    fun allowSafeToolGoesThroughPipelineAndExecutes() {
        val reg = ToolRegistry().apply { register(FlashTool()) }
        val orch = ExecutionOrchestrator(DefaultToolExecutor(reg))
        val call = ToolCall(requestId = "req-1", toolId = "flashlight", arguments = mapOf("action" to "on"))
        val out = orch.executeAction(call, reg.find("flashlight")!!.definition, requestId = "req-1")
        assertTrue(out is ExecutionOrchestrator.Outcome.Executed)
        val exec = out as ExecutionOrchestrator.Outcome.Executed
        assertEquals(ToolResultStatus.SUCCESS, exec.toolResult.status)
        assertEquals(AutonomyPlanStatus.READY_TO_EXECUTE, exec.autonomyStatus)
        assertNotNull(exec.verification)
    }

    @Test
    fun blockedToolDeniedWithoutExecute() {
        val reg = ToolRegistry().apply { register(BlockedTool()) }
        val orch = ExecutionOrchestrator(DefaultToolExecutor(reg))
        val call = ToolCall(requestId = "req-2", toolId = "blocked_demo", arguments = emptyMap())
        val out = orch.executeAction(call, reg.find("blocked_demo")!!.definition, requestId = "req-2")
        assertTrue(out is ExecutionOrchestrator.Outcome.Denied)
    }

    @Test
    fun confirmToolNeedsConfirmationThenExecutesAfterYes() {
        val tool = ConfirmTool()
        val reg = ToolRegistry().apply { register(tool) }
        val orch = ExecutionOrchestrator(DefaultToolExecutor(reg))
        val call = ToolCall(requestId = "req-3", toolId = "phone.call", arguments = mapOf("contact" to "Мама"))
        val first = orch.executeAction(call, tool.definition, requestId = "req-3")
        assertTrue(first is ExecutionOrchestrator.Outcome.NeedsConfirmation)
        assertFalse(tool.executed)

        val second = orch.executeAction(
            call,
            tool.definition,
            requestId = "req-3",
            userConfirmed = true,
            priorFingerprint = (first as ExecutionOrchestrator.Outcome.NeedsConfirmation)
                .integrityFingerprint,
        )
        assertTrue(second is ExecutionOrchestrator.Outcome.Executed)
        assertTrue(tool.executed)
    }

    @Test
    fun riskBridgePreservesSafeAsLocalDeviceSafe() {
        val bridge = LiveSkillRiskBridge()
        assertEquals(
            ActionKind.LOCAL_DEVICE_SAFE,
            bridge.kindForRiskLevel(RiskLevel.SAFE, "flashlight", ActionKind.LOCAL_DEVICE_SAFE),
        )
        assertEquals(
            ActionKind.READ_ONLY,
            bridge.kindForRiskLevel(RiskLevel.SAFE, "weather", ActionKind.READ_ONLY),
        )
        assertEquals(
            ActionKind.UNKNOWN,
            bridge.kindForRiskLevel(RiskLevel.BLOCKED, "x", null),
        )
    }

    @Test
    fun requestTracePreservesRequestIdAndRoute() {
        val t = RequestTrace(
            requestId = "abc",
            timestampMillis = 1L,
            inputType = "VOICE",
        )
        t.route = RouteDecision.FAST_SKILL
        t.routeReason = "skill:flashlight"
        t.tool = "flashlight"
        assertEquals("abc", t.requestId)
        assertEquals("abc/step-1", t.childStepId(1))
        assertTrue(t.summaryLine().contains("route=FAST_SKILL"))
        assertTrue(t.summaryLine().contains("req=abc"))
    }

    @Test
    fun featureLifecycleDistinguishesConstructedFromActive() {
        val snap = FeatureStatusSnapshot(
            capturedAtMillis = 1L,
            features = listOf(
                FeatureStatus("memory2", FeatureLifecycle.CONSTRUCTED, "not_on_live_path"),
                FeatureStatus("executionOrchestrator", FeatureLifecycle.ACTIVE_ON_LIVE_PATH),
            ),
        )
        assertEquals(FeatureLifecycle.CONSTRUCTED, snap.find("memory2")!!.lifecycle)
        assertEquals(FeatureLifecycle.ACTIVE_ON_LIVE_PATH, snap.find("executionOrchestrator")!!.lifecycle)
        assertTrue(snap.lines().any { it.contains("memory2=constructed") })
        assertFalse(snap.lines().any { it == "memory2=on" })
    }

    @Test
    fun routeDecisionCoversRequiredRoutes() {
        val names = RouteDecision.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("FAST_SKILL", "CHAT", "PLAN", "CLARIFY", "TEMPLATE", "UNKNOWN")))
    }
}
