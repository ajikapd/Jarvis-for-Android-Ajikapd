package com.jarvis.core.filesagent

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.ActionPlanValidator
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.planner.UserGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesAgentTest {

    private val agent = FilesAgent()

    @Test
    fun searchPlanSingleToolNoConfirm() {
        val plan = agent.plan(
            FileAgentRequest(
                goal = UserGoal("g1", "найди фото отпуск"),
                operation = FileOperation.SEARCH,
                query = "отпуск",
            ),
        )
        assertEquals(ActionPlanStatus.READY, plan.status)
        assertEquals(1, plan.steps.size)
        assertEquals(PlanStepKind.TOOL, plan.steps[0].kind)
        assertEquals("files.search", plan.steps[0].toolId)
        assertEquals("отпуск", plan.steps[0].parameters["query"])
        assertFalse(plan.steps[0].requiresConfirmation)
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun deleteRequiresConfirmAndSensitive() {
        assertEquals(RiskLevel.SENSITIVE, FileOperationPolicy.riskLevel(FileOperation.DELETE))
        val plan = agent.plan(
            FileAgentRequest(
                goal = UserGoal("g2", "удали файл"),
                operation = FileOperation.DELETE,
                path = "/sdcard/x.txt",
            ),
        )
        assertTrue(plan.steps.any { it.kind == PlanStepKind.CONFIRM })
        assertTrue(plan.steps.any { it.kind == PlanStepKind.TOOL && it.requiresConfirmation })
        assertEquals("files.delete", plan.steps.last().toolId)
        assertTrue(ActionPlanValidator.validate(plan).ok)
    }

    @Test
    fun inferDeleteFromRussian() {
        assertEquals(FileOperation.DELETE, agent.inferOperation("удали старые фото"))
    }

    @Test
    fun inferSearchDefault() {
        assertEquals(FileOperation.SEARCH, agent.inferOperation("найди музыку beatles"))
    }

    @Test
    fun mutatingNeedsConfirm() {
        for (op in listOf(FileOperation.CREATE, FileOperation.RENAME, FileOperation.MOVE, FileOperation.ORGANIZE)) {
            assertTrue(op.name, FileOperationPolicy.requiresConfirmation(op))
        }
    }

    @Test
    fun planFromGoalSearchUsesGoalTextAsQuery() {
        val plan = agent.planFromGoal(UserGoal("g3", "найди договор"))
        assertEquals(FileOperation.SEARCH, agent.inferOperation("найди договор"))
        assertEquals("files.search", plan.steps.single().toolId)
        assertEquals("найди договор", plan.steps.single().parameters["query"])
    }

    @Test
    fun readIsSafeRead() {
        assertEquals(FileOpRiskHint.SAFE_READ, FileOperationPolicy.riskHint(FileOperation.READ))
        assertFalse(FileOperationPolicy.requiresConfirmation(FileOperation.READ))
    }
}
