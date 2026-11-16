package com.jarvis.core.planner

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.discovery.DiscoveredItem
import com.jarvis.core.discovery.DiscoveryResult
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolProviderSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicAIPlannerTest {

    private val planner = DeterministicAIPlanner()

    private fun item(
        id: String,
        score: Float = 1f,
        risk: RiskLevel = RiskLevel.LOW,
        params: List<String> = emptyList(),
        name: String = id,
    ) = DiscoveredItem(
        toolId = id,
        capabilityId = "cap.$id",
        name = name,
        description = "desc $id",
        score = score,
        riskLevel = risk,
        requiredPermissions = emptyList(),
        availability = ToolAvailability.ALWAYS,
        source = ToolProviderSource.NATIVE,
        locality = ToolExecutionLocality.LOCAL,
        hasExecutable = true,
        parameterNames = params,
    )

    private fun discovery(vararg items: DiscoveredItem) = DiscoveryResult(
        query = "test",
        items = items.toList(),
        truncated = false,
        considered = items.size,
    )

    @Test
    fun emptyDiscoveryYieldsEmptyPlan() {
        val plan = planner.plan(
            UserGoal("g1", "что-нибудь"),
            PlannerContext(discovery = discovery()),
        )
        assertEquals(ActionPlanStatus.EMPTY, plan.status)
        assertTrue(plan.steps.isEmpty())
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun singleStepToolPlan() {
        val plan = planner.plan(
            UserGoal("g2", "включи фонарик"),
            PlannerContext(
                discovery = discovery(item("flashlight", score = 0.9f, name = "Flashlight")),
                preferSingleStep = true,
            ),
        )
        assertEquals(ActionPlanStatus.READY, plan.status)
        assertEquals(1, plan.steps.size)
        assertEquals(PlanStepKind.TOOL, plan.steps[0].kind)
        assertEquals("flashlight", plan.steps[0].toolId)
        assertEquals("s1-tool", plan.entryStepId)
        assertTrue(plan.resultSlots.containsKey("s1-tool"))
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun highRiskInsertsConfirmBeforeTool() {
        val plan = planner.plan(
            UserGoal("g3", "отправь смс"),
            PlannerContext(
                discovery = discovery(item("sms", risk = RiskLevel.CONFIRM, name = "SMS")),
            ),
        )
        assertEquals(ActionPlanStatus.READY, plan.status)
        assertEquals(2, plan.steps.size)
        assertEquals(PlanStepKind.CONFIRM, plan.steps[0].kind)
        assertEquals(PlanStepKind.TOOL, plan.steps[1].kind)
        assertTrue(plan.steps[1].dependsOn.contains("s1-confirm"))
        assertEquals(PlanConditionOp.PREV_SUCCESS, plan.steps[1].condition.op)
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun multiStepSequenceWithDepsAndBranch() {
        val plan = planner.plan(
            UserGoal("g4", "фонарик и громкость"),
            PlannerContext(
                discovery = discovery(
                    item("flashlight", score = 0.9f),
                    item("volume", score = 0.8f),
                ),
                preferSingleStep = false,
                maxSteps = 4,
            ),
        )
        assertEquals(ActionPlanStatus.READY, plan.status)
        assertTrue(plan.steps.any { it.kind == PlanStepKind.TOOL && it.toolId == "flashlight" })
        assertTrue(plan.steps.any { it.kind == PlanStepKind.TOOL && it.toolId == "volume" })
        assertTrue(plan.steps.any { it.kind == PlanStepKind.BRANCH })
        assertTrue(plan.steps.any { it.kind == PlanStepKind.NOOP })
        val volume = plan.steps.first { it.toolId == "volume" }
        assertTrue(volume.dependsOn.isNotEmpty())
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun waitBetweenStepsWhenConfigured() {
        val plan = planner.plan(
            UserGoal("g5", "два шага"),
            PlannerContext(
                discovery = discovery(
                    item("flashlight", score = 1f),
                    item("volume", score = 0.5f),
                ),
                preferSingleStep = false,
                defaultWaitBetweenMs = 100L,
            ),
        )
        assertTrue(plan.steps.any { it.kind == PlanStepKind.WAIT && it.waitMs == 100L })
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun cancelMarksCancelled() {
        val plan = planner.plan(
            UserGoal("g6", "x"),
            PlannerContext(discovery = discovery(item("flashlight"))),
        )
        val cancelled = planner.cancel(plan, "user")
        assertEquals(ActionPlanStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.cancellationRequested)
        assertTrue(ActionPlanValidator.validate(cancelled).ok)
    }

    @Test
    fun prefersHigherScoreWhenSingleStep() {
        val plan = planner.plan(
            UserGoal("g7", "x"),
            PlannerContext(
                discovery = discovery(
                    item("volume", score = 0.2f),
                    item("flashlight", score = 0.95f),
                ),
            ),
        )
        assertEquals("flashlight", plan.steps.single().toolId)
    }

    @Test
    fun parameterBindingsAndResultConditionValidate() {
        val steps = listOf(
            PlanStep(
                stepId = "a",
                kind = PlanStepKind.TOOL,
                toolId = "flashlight",
                parameters = mapOf("mode" to "on"),
            ),
            PlanStep(
                stepId = "b",
                kind = PlanStepKind.TOOL,
                toolId = "volume",
                dependsOn = listOf("a"),
                condition = PlanStepCondition(
                    op = PlanConditionOp.RESULT_EQUALS,
                    dependsOnStepId = "a",
                    resultKey = "output",
                    expectedValue = "ok",
                ),
                parameterBindings = listOf(
                    PlanParameterBinding("level", fromStepId = "a", fromKey = "output"),
                ),
            ),
        )
        val plan = ActionPlan(
            planId = "p",
            goalId = "g",
            status = ActionPlanStatus.READY,
            steps = steps,
        )
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun cycleDetectedAsInvalid() {
        // Construct invalid plan bypassing planner
        val a = PlanStep(
            stepId = "a",
            kind = PlanStepKind.TOOL,
            toolId = "flashlight",
            dependsOn = listOf("b"),
        )
        val b = PlanStep(
            stepId = "b",
            kind = PlanStepKind.TOOL,
            toolId = "volume",
            dependsOn = listOf("a"),
        )
        val plan = ActionPlan(
            planId = "p",
            goalId = "g",
            status = ActionPlanStatus.READY,
            steps = listOf(a, b),
        )
        val v = ActionPlanValidator.validate(plan)
        assertFalse(v.ok)
        assertTrue(v.errors.any { it.contains("cycle") })
    }

    @Test
    fun retryAndConfirmationFieldsPresent() {
        val plan = planner.plan(
            UserGoal("g8", "sms"),
            PlannerContext(discovery = discovery(item("sms", risk = RiskLevel.SENSITIVE))),
        )
        val tool = plan.steps.first { it.kind == PlanStepKind.TOOL }
        assertTrue(tool.requiresConfirmation)
        assertTrue(tool.cancelOnFailure)
        assertEquals(1, tool.retry.maxAttempts)
        assertNotNull(plan.step(tool.stepId))
        assertNull(plan.step("missing"))
    }
}
