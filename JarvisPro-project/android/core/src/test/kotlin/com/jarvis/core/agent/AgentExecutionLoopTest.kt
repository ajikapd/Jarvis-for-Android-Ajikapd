package com.jarvis.core.agent

import com.jarvis.core.autonomy.AutonomyActionPipeline
import com.jarvis.core.autonomy.AutonomyLevel
import com.jarvis.core.autonomy.DefaultActionSafetyGate
import com.jarvis.core.autonomy.ProposedActionMapper
import com.jarvis.core.autonomy.SafeAutonomousExecutor
import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.discovery.DiscoveredItem
import com.jarvis.core.discovery.DiscoveryResult
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolProviderSource
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.DeterministicAIPlanner
import com.jarvis.core.planner.PlanStep
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.planner.PlannerContext
import com.jarvis.core.planner.UserGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionLoopTest {

    private val planner = DeterministicAIPlanner()

    private fun item(
        id: String,
        score: Float = 1f,
        risk: RiskLevel = RiskLevel.LOW,
    ) = DiscoveredItem(
        toolId = id,
        capabilityId = "cap.$id",
        name = id,
        description = id,
        score = score,
        riskLevel = risk,
        requiredPermissions = emptyList(),
        availability = ToolAvailability.ALWAYS,
        source = ToolProviderSource.NATIVE,
        locality = ToolExecutionLocality.LOCAL,
        hasExecutable = true,
        parameterNames = listOf("action"),
    )

    private fun discovery(vararg items: DiscoveredItem) = DiscoveryResult(
        query = "q",
        items = items.toList(),
        truncated = false,
        considered = items.size,
    )

    private fun fakeFlashlight(ok: Boolean = true): Tool = object : Tool {
        override val definition = ToolDefinition(
            id = "flashlight",
            name = "Flashlight",
            description = "t",
            riskLevel = RiskLevel.SAFE,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        )
        override fun execute(call: ToolCall): ToolResult = ToolResult(
            requestId = call.requestId,
            toolId = "flashlight",
            status = if (ok) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            message = if (ok) "on" else "fail",
        )
    }

    @Test
    fun dryRunFlashlightCompletes() {
        val plan = planner.plan(
            UserGoal("g1", "фонарик"),
            PlannerContext(discovery = discovery(item("flashlight"))),
        )
        val loop = AgentExecutionLoop(pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()))
        val result = loop.run(plan, AgentLoopContext(live = false, nowMillis = 1_000_000L))
        assertEquals(
            "unexpected: completed=${result.completed} reason=${result.stoppedReason} " +
                "records=${result.records}",
            "completed",
            result.stoppedReason,
        )
        assertTrue(result.completed)
        assertEquals(StepRunStatus.SUCCESS, result.records.single().status)
        assertFalse(result.records.single().executed)
        assertEquals("dry_run_ready", result.records.single().message)
    }

    @Test
    fun liveSafeExecutesFlashlight() {
        val plan = planner.plan(
            UserGoal("g2", "фонарик"),
            PlannerContext(discovery = discovery(item("flashlight"))),
        )
        val tools = ToolRegistry().apply { register(fakeFlashlight()) }
        val pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate())
        val safe = SafeAutonomousExecutor(DefaultToolExecutor(tools), pipeline)
        val loop = AgentExecutionLoop(pipeline = pipeline, safeExecutor = safe)
        val result = loop.run(
            plan,
            AgentLoopContext(live = true, nowMillis = 1_000_000L),
        )
        assertTrue(result.completed)
        assertTrue(result.records.single().executed)
        assertEquals(StepRunStatus.SUCCESS, result.records.single().status)
    }

    @Test
    fun confirmStopsWithoutConfirmation() {
        val plan = planner.plan(
            UserGoal("g3", "sms"),
            PlannerContext(discovery = discovery(item("sms", risk = RiskLevel.CONFIRM))),
        )
        assertTrue(plan.steps.any { it.kind == PlanStepKind.CONFIRM })
        val loop = AgentExecutionLoop(pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()))
        val result = loop.run(plan, AgentLoopContext(live = false, nowMillis = 1_000_000L))
        assertFalse(result.completed)
        assertEquals(StepRunStatus.AWAITING_CONFIRMATION, result.records.first().status)
        assertEquals(LoopDecision.STOP, result.finalDecision)
    }

    @Test
    fun confirmThenToolWhenConfirmed() {
        val plan = planner.plan(
            UserGoal("g4", "sms"),
            PlannerContext(discovery = discovery(item("sms", risk = RiskLevel.CONFIRM))),
        )
        val confirmId = plan.steps.first { it.kind == PlanStepKind.CONFIRM }.stepId
        val loop = AgentExecutionLoop(pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()))
        val result = loop.run(
            plan,
            AgentLoopContext(
                live = false,
                confirmedStepIds = setOf(confirmId),
                nowMillis = 1_000_000L,
            ),
        )
        assertTrue(result.records.any { it.status == StepRunStatus.SUCCESS && it.message == "confirmed" })
        // sms may be denied by SAFE_ONLY policy after confirm — either SUCCESS dry_run or BLOCKED/STOP
        assertTrue(result.records.size >= 2)
    }

    @Test
    fun unknownToolRequestsModify() {
        val plan = ActionPlan(
            planId = "p",
            goalId = "g",
            status = ActionPlanStatus.READY,
            steps = listOf(
                PlanStep(
                    stepId = "s1",
                    kind = PlanStepKind.TOOL,
                    toolId = "totally.unknown.tool",
                    displaySummary = "x",
                ),
            ),
        )
        val loop = AgentExecutionLoop()
        val result = loop.run(plan, AgentLoopContext(nowMillis = 1_000_000L))
        assertEquals(LoopDecision.MODIFY, result.finalDecision)
        assertEquals("unknown_tool_mapping", result.records.single().message)
    }

    @Test
    fun cancelledPlanStopsImmediately() {
        val plan = planner.plan(
            UserGoal("g5", "фонарик"),
            PlannerContext(discovery = discovery(item("flashlight"))),
        )
        val cancelled = planner.cancel(plan)
        val result = AgentExecutionLoop().run(cancelled)
        assertFalse(result.completed)
        assertEquals("plan_cancelled", result.stoppedReason)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun emptyPlanStops() {
        val plan = planner.plan(
            UserGoal("g6", "ничего"),
            PlannerContext(discovery = discovery()),
        )
        val result = AgentExecutionLoop().run(plan)
        assertEquals("plan_not_ready:EMPTY", result.stoppedReason)
    }

    @Test
    fun multiStepDryRunSequential() {
        val plan = planner.plan(
            UserGoal("g7", "два"),
            PlannerContext(
                discovery = discovery(item("flashlight", 0.9f), item("volume", 0.8f)),
                preferSingleStep = false,
            ),
        )
        val loop = AgentExecutionLoop(pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()))
        val result = loop.run(plan, AgentLoopContext(live = false, nowMillis = 1_000_000L))
        assertTrue(result.records.count { it.toolId != null && it.status == StepRunStatus.SUCCESS } >= 2)
        assertTrue(result.completed)
    }

    @Test
    fun autonomyOffBlocksLiveSafe() {
        val plan = planner.plan(
            UserGoal("g8", "фонарик"),
            PlannerContext(discovery = discovery(item("flashlight"))),
        )
        val tools = ToolRegistry().apply { register(fakeFlashlight()) }
        val pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate())
        val safe = SafeAutonomousExecutor(DefaultToolExecutor(tools), pipeline)
        val result = AgentExecutionLoop(pipeline, safeExecutor = safe).run(
            plan,
            AgentLoopContext(
                live = true,
                autonomyLevel = AutonomyLevel.OFF,
                nowMillis = 1_000_000L,
            ),
        )
        assertFalse(result.completed)
        assertTrue(
            result.records.any {
                it.status == StepRunStatus.FAILURE || it.status == StepRunStatus.BLOCKED
            },
        )
    }
}
