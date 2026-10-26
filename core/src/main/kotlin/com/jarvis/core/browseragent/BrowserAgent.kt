package com.jarvis.core.browseragent

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.ActionPlanValidator
import com.jarvis.core.planner.PlanConditionOp
import com.jarvis.core.planner.PlanRetryPolicy
import com.jarvis.core.planner.PlanStep
import com.jarvis.core.planner.PlanStepCondition
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.planner.UserGoal
import java.util.UUID

/**
 * Stage 18: шаги Web / Browser Agent.
 * Browser actions — обычные Tools в ActionPlan (не отдельный executor).
 */
enum class BrowserStepKind {
    SEARCH,
    OPEN,
    INSPECT,
    EXTRACT,
    ACT,
    VERIFY,
}

data class BrowserAgentRequest(
    val goal: UserGoal,
    val steps: List<BrowserStepKind> = DEFAULT_PIPELINE,
    val query: String? = null,
    val url: String? = null,
    val isOnline: Boolean = true,
) {
    init {
        require(steps.isNotEmpty() && steps.size <= ActionPlan.MAX_STEPS)
        query?.let { require(it.length <= 400) }
        url?.let { require(it.length <= 1_000) }
    }

    companion object {
        val DEFAULT_PIPELINE: List<BrowserStepKind> = listOf(
            BrowserStepKind.SEARCH,
            BrowserStepKind.OPEN,
            BrowserStepKind.INSPECT,
            BrowserStepKind.EXTRACT,
            BrowserStepKind.ACT,
            BrowserStepKind.VERIFY,
        )
    }
}

object BrowserStepPolicy {
    fun riskLevel(kind: BrowserStepKind): RiskLevel = when (kind) {
        BrowserStepKind.SEARCH,
        BrowserStepKind.INSPECT,
        BrowserStepKind.EXTRACT,
        BrowserStepKind.VERIFY,
        -> RiskLevel.LOW
        BrowserStepKind.OPEN -> RiskLevel.CONFIRM
        BrowserStepKind.ACT -> RiskLevel.SENSITIVE
    }

    fun requiresConfirmation(kind: BrowserStepKind): Boolean =
        kind == BrowserStepKind.OPEN || kind == BrowserStepKind.ACT

    fun requiresOnline(kind: BrowserStepKind): Boolean = true

    fun toolId(kind: BrowserStepKind): String = "browser.${kind.name.lowercase()}"
}

/**
 * Stage 18: Web / Browser Agent — строит ActionPlan:
 * Search → Open → Inspect → Extract → Act → Verify
 *
 * Требует online. Опасные ACT/OPEN → CONFIRM. Не network client.
 */
class BrowserAgent {

    fun plan(request: BrowserAgentRequest): ActionPlan {
        val planId = "browser-${UUID.randomUUID()}"
        if (!request.isOnline) {
            return ActionPlan(
                planId = planId,
                goalId = request.goal.goalId,
                status = ActionPlanStatus.EMPTY,
                steps = emptyList(),
                notes = listOf("offline_browser_unavailable"),
            )
        }

        val steps = mutableListOf<PlanStep>()
        var prevId: String? = null

        for ((index, kind) in request.steps.withIndex()) {
            val needsConfirm = BrowserStepPolicy.requiresConfirmation(kind)
            if (needsConfirm) {
                val confirmId = "b${index + 1}-confirm"
                steps += PlanStep(
                    stepId = confirmId,
                    kind = PlanStepKind.CONFIRM,
                    requiresConfirmation = true,
                    dependsOn = prevId?.let { listOf(it) }.orEmpty(),
                    condition = prevSuccess(prevId),
                    displaySummary = "Confirm browser ${kind.name.lowercase()}",
                    notes = listOf("risk=${BrowserStepPolicy.riskLevel(kind)}"),
                )
                prevId = confirmId
            }

            val toolStepId = "b${index + 1}-${kind.name.lowercase()}"
            val params = linkedMapOf<String, String>()
            when (kind) {
                BrowserStepKind.SEARCH ->
                    params["query"] = request.query ?: request.goal.text.take(200)
                BrowserStepKind.OPEN ->
                    request.url?.let { params["url"] = it }
                        ?: params.put("url", "")
                else -> Unit
            }
            steps += PlanStep(
                stepId = toolStepId,
                kind = PlanStepKind.TOOL,
                toolId = BrowserStepPolicy.toolId(kind),
                parameters = params.filterValues { it.isNotBlank() },
                dependsOn = prevId?.let { listOf(it) }.orEmpty(),
                condition = prevSuccess(prevId),
                requiresConfirmation = needsConfirm,
                retry = PlanRetryPolicy(
                    maxAttempts = if (kind == BrowserStepKind.SEARCH) 2 else 1,
                ),
                cancelOnFailure = needsConfirm,
                displaySummary = kind.name,
                notes = listOf(
                    "browser_agent=true",
                    "step=$kind",
                    "risk=${BrowserStepPolicy.riskLevel(kind)}",
                    "online=required",
                ),
            )
            prevId = toolStepId
        }

        var plan = ActionPlan(
            planId = planId,
            goalId = request.goal.goalId,
            status = ActionPlanStatus.READY,
            steps = steps,
            entryStepId = steps.firstOrNull()?.stepId,
            resultSlots = steps.filter { it.kind == PlanStepKind.TOOL }
                .associate { it.stepId to "output" },
            notes = listOf(
                "planner=browser_agent",
                "pipeline=${request.steps.joinToString(">")}",
            ),
        )
        val v = ActionPlanValidator.validate(plan)
        if (!v.ok) {
            plan = plan.copy(
                status = ActionPlanStatus.INVALID,
                notes = plan.notes + v.errors.map { "err=$it" },
            )
        }
        return plan
    }

    fun planFromGoal(goal: UserGoal, isOnline: Boolean = true): ActionPlan =
        plan(BrowserAgentRequest(goal = goal, isOnline = isOnline))

    private fun prevSuccess(prevId: String?): PlanStepCondition =
        if (prevId == null) PlanStepCondition()
        else PlanStepCondition(PlanConditionOp.PREV_SUCCESS, dependsOnStepId = prevId)
}
