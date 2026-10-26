package com.jarvis.core.browseragent

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.ActionPlanValidator
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.planner.UserGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAgentTest {

    private val agent = BrowserAgent()

    @Test
    fun offlineYieldsEmpty() {
        val plan = agent.planFromGoal(UserGoal("g1", "найди новости"), isOnline = false)
        assertEquals(ActionPlanStatus.EMPTY, plan.status)
        assertTrue(plan.notes.any { it.contains("offline") })
    }

    @Test
    fun defaultPipelineHasSearchOpenActWithConfirms() {
        val plan = agent.plan(
            BrowserAgentRequest(
                goal = UserGoal("g2", "найди погоду в москве"),
                query = "погода москва",
                isOnline = true,
            ),
        )
        assertEquals(ActionPlanStatus.READY, plan.status)
        assertTrue(plan.steps.any { it.toolId == "browser.search" })
        assertTrue(plan.steps.any { it.toolId == "browser.open" })
        assertTrue(plan.steps.any { it.toolId == "browser.act" })
        assertTrue(plan.steps.any { it.toolId == "browser.verify" })
        // OPEN and ACT need confirm steps
        assertEquals(2, plan.steps.count { it.kind == PlanStepKind.CONFIRM })
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun searchOnlyPipeline() {
        val plan = agent.plan(
            BrowserAgentRequest(
                goal = UserGoal("g3", "search"),
                steps = listOf(BrowserStepKind.SEARCH),
                query = "kotlin",
            ),
        )
        assertEquals(1, plan.steps.size)
        assertEquals("browser.search", plan.steps[0].toolId)
        assertEquals("kotlin", plan.steps[0].parameters["query"])
        assertFalse(plan.steps[0].requiresConfirmation)
    }

    @Test
    fun actIsSensitive() {
        assertEquals(RiskLevel.SENSITIVE, BrowserStepPolicy.riskLevel(BrowserStepKind.ACT))
        assertTrue(BrowserStepPolicy.requiresConfirmation(BrowserStepKind.ACT))
    }

    @Test
    fun openIsConfirm() {
        assertEquals(RiskLevel.CONFIRM, BrowserStepPolicy.riskLevel(BrowserStepKind.OPEN))
        assertTrue(BrowserStepPolicy.requiresConfirmation(BrowserStepKind.OPEN))
    }

    @Test
    fun inspectExtractVerifyAreLowRisk() {
        for (k in listOf(BrowserStepKind.INSPECT, BrowserStepKind.EXTRACT, BrowserStepKind.VERIFY)) {
            assertEquals(RiskLevel.LOW, BrowserStepPolicy.riskLevel(k))
            assertFalse(BrowserStepPolicy.requiresConfirmation(k))
        }
    }
}
