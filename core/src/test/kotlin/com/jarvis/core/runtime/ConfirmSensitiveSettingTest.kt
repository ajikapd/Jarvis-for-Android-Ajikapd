package com.jarvis.core.runtime

import com.jarvis.core.autonomy.AutonomyPlanStatus
import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * confirm_sensitive (по умолчанию true) ослабляет подтверждение **только**
 * для [RiskLevel.CONFIRM]. SENSITIVE/BLOCKED и SAFE/LOW не меняются.
 *
 * Настройка приходит в core лямбдой [ExecutionOrchestrator.confirmLevelRequired],
 * поэтому тест не зависит от Android Context.
 */
class ConfirmSensitiveSettingTest {

    private class ProbeTool(override val definition: ToolDefinition) : Tool {
        @Volatile
        var executed: Boolean = false

        override fun execute(call: ToolCall): ToolResult {
            executed = true
            return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "ok")
        }
    }

    private fun probe(id: String, risk: RiskLevel) = ProbeTool(
        ToolDefinition(
            id = id,
            name = id,
            description = "test probe",
            riskLevel = risk,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        ),
    )

    private fun orchestrator(confirmSensitive: Boolean, tool: Tool): ExecutionOrchestrator {
        val registry = ToolRegistry().apply { register(tool) }
        return ExecutionOrchestrator(
            toolExecutor = DefaultToolExecutor(registry),
            confirmLevelRequired = { confirmSensitive },
        )
    }

    private fun call(toolId: String, args: Map<String, Any>) =
        ToolCall(requestId = "req-$toolId", toolId = toolId, arguments = args)

    // ── CONFIRM: подтверждение требуется при confirm_sensitive=true ─────

    @Test
    fun smsRequiresConfirmationWhenSettingTrue() {
        val tool = probe("sms", RiskLevel.CONFIRM)
        val orch = orchestrator(confirmSensitive = true, tool = tool)
        val out = orch.executeAction(
            call("sms", mapOf("action" to "send", "recipient" to "Мама", "text" to "привет")),
            tool.definition,
            requestId = "req-sms-true",
        )
        assertTrue(out is ExecutionOrchestrator.Outcome.NeedsConfirmation)
        assertFalse(tool.executed)
    }

    @Test
    fun telegramRequiresConfirmationWhenSettingTrue() {
        val tool = probe("telegram.send", RiskLevel.CONFIRM)
        val orch = orchestrator(confirmSensitive = true, tool = tool)
        val out = orch.executeAction(
            call("telegram.send", mapOf("chat" to "Мама", "text" to "привет")),
            tool.definition,
            requestId = "req-tg-true",
        )
        assertTrue(out is ExecutionOrchestrator.Outcome.NeedsConfirmation)
        assertFalse(tool.executed)
    }

    // ── CONFIRM: подтверждение снимается при confirm_sensitive=false ────

    @Test
    fun smsExecutesWithoutConfirmationWhenSettingFalse() {
        val tool = probe("sms", RiskLevel.CONFIRM)
        val orch = orchestrator(confirmSensitive = false, tool = tool)
        val out = orch.executeAction(
            call("sms", mapOf("action" to "send", "recipient" to "Мама", "text" to "привет")),
            tool.definition,
            requestId = "req-sms-false",
        )
        assertTrue(out is ExecutionOrchestrator.Outcome.Executed)
        assertEquals(
            AutonomyPlanStatus.READY_TO_EXECUTE,
            (out as ExecutionOrchestrator.Outcome.Executed).autonomyStatus,
        )
        assertTrue(tool.executed)
    }

    @Test
    fun telegramExecutesWithoutConfirmationWhenSettingFalse() {
        val tool = probe("telegram.send", RiskLevel.CONFIRM)
        val orch = orchestrator(confirmSensitive = false, tool = tool)
        val out = orch.executeAction(
            call("telegram.send", mapOf("chat" to "Мама", "text" to "привет")),
            tool.definition,
            requestId = "req-tg-false",
        )
        assertTrue(out is ExecutionOrchestrator.Outcome.Executed)
        assertTrue(tool.executed)
    }

    // ── Всё, что опаснее CONFIRM, настройкой не обходится ───────────────

    @Test
    fun sensitiveStillRequiresConfirmationWhenSettingFalse() {
        val tool = probe("demo.sensitive", RiskLevel.SENSITIVE)
        val orch = orchestrator(confirmSensitive = false, tool = tool)
        val out = orch.executeAction(
            call("demo.sensitive", mapOf("target" to "bank")),
            tool.definition,
            requestId = "req-sensitive-false",
        )
        assertTrue(out is ExecutionOrchestrator.Outcome.NeedsConfirmation)
        assertFalse(tool.executed)
    }

    @Test
    fun blockedStillDeniedWhenSettingFalse() {
        val tool = probe("demo.blocked", RiskLevel.BLOCKED)
        val orch = orchestrator(confirmSensitive = false, tool = tool)
        val out = orch.executeAction(
            call("demo.blocked", emptyMap()),
            tool.definition,
            requestId = "req-blocked-false",
        )
        assertTrue(out is ExecutionOrchestrator.Outcome.Denied)
        assertFalse(tool.executed)
    }

    // ── SAFE / LOW не затрагиваются настройкой ──────────────────────────

    @Test
    fun safeToolUnaffectedBySetting() {
        for (setting in listOf(true, false)) {
            val tool = probe("flashlight", RiskLevel.SAFE)
            val orch = orchestrator(confirmSensitive = setting, tool = tool)
            val out = orch.executeAction(
                call("flashlight", mapOf("action" to "on")),
                tool.definition,
                requestId = "req-safe-$setting",
            )
            assertTrue("setting=$setting", out is ExecutionOrchestrator.Outcome.Executed)
            assertTrue("setting=$setting", tool.executed)
        }
    }

    @Test
    fun lowRiskToolUnaffectedBySetting() {
        for (setting in listOf(true, false)) {
            val tool = probe("clipboard", RiskLevel.LOW)
            val orch = orchestrator(confirmSensitive = setting, tool = tool)
            val out = orch.executeAction(
                call("clipboard", mapOf("action" to "read")),
                tool.definition,
                requestId = "req-low-$setting",
            )
            assertTrue("setting=$setting", out is ExecutionOrchestrator.Outcome.Executed)
            assertTrue("setting=$setting", tool.executed)
        }
    }
}
