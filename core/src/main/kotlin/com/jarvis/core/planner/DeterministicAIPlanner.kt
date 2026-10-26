package com.jarvis.core.planner

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.discovery.DiscoveredItem
import java.util.UUID

/**
 * Stage 11: детерминированный Planner без LLM.
 *
 * Строит план из Discovery subset:
 * - 0 tools → EMPTY
 * - preferSingleStep → один TOOL (+ CONFIRM перед ним если risk ≥ CONFIRM)
 * - иначе → последовательность TOOL с зависимостями (+ optional WAIT / terminal BRANCH)
 *
 * Не вызывает tools. Не обходит autonomy.
 */
class DeterministicAIPlanner : AIPlanner {

    override fun plan(goal: UserGoal, context: PlannerContext): ActionPlan {
        val planId = "plan-${UUID.randomUUID()}"
        val items = context.discovery.items
            .filter { it.toolId.isNotBlank() }
            .sortedByDescending { it.score }

        if (items.isEmpty()) {
            return ActionPlan(
                planId = planId,
                goalId = goal.goalId,
                status = ActionPlanStatus.EMPTY,
                steps = emptyList(),
                notes = listOf(
                    "no_discovered_tools",
                    "discovery_considered=${context.discovery.considered}",
                ),
            )
        }

        val selected = if (context.preferSingleStep) {
            listOf(items.first())
        } else {
            items.take(context.maxSteps.coerceAtMost(ActionPlan.MAX_STEPS))
        }

        val steps = mutableListOf<PlanStep>()
        var prevId: String? = null

        for ((index, item) in selected.withIndex()) {
            val needsConfirm = item.riskLevel.ordinal >= RiskLevel.CONFIRM.ordinal

            if (needsConfirm) {
                val confirmId = "s${index + 1}-confirm"
                steps += PlanStep(
                    stepId = confirmId,
                    kind = PlanStepKind.CONFIRM,
                    requiresConfirmation = true,
                    dependsOn = prevId?.let { listOf(it) }.orEmpty(),
                    condition = prevSuccess(prevId),
                    displaySummary = "Confirm: ${item.name}",
                    notes = listOf("risk=${item.riskLevel}", "tool=${item.toolId}"),
                )
                prevId = confirmId
            }

            if (context.defaultWaitBetweenMs > 0L && prevId != null && index > 0) {
                val waitId = "s${index + 1}-wait"
                steps += PlanStep(
                    stepId = waitId,
                    kind = PlanStepKind.WAIT,
                    waitMs = context.defaultWaitBetweenMs,
                    dependsOn = listOf(prevId!!),
                    condition = prevSuccess(prevId),
                    displaySummary = "Wait ${context.defaultWaitBetweenMs}ms",
                )
                prevId = waitId
            }

            val toolStepId = "s${index + 1}-tool"
            steps += PlanStep(
                stepId = toolStepId,
                kind = PlanStepKind.TOOL,
                toolId = item.toolId,
                capabilityId = item.capabilityId,
                parameters = boundedParams(item),
                dependsOn = prevId?.let { listOf(it) }.orEmpty(),
                condition = prevSuccess(prevId),
                requiresConfirmation = needsConfirm,
                retry = PlanRetryPolicy(
                    maxAttempts = if (item.riskLevel == RiskLevel.LOW) 2 else 1,
                ),
                cancelOnFailure = needsConfirm,
                displaySummary = item.name.ifBlank { item.toolId },
                notes = listOf(
                    "score=${item.score}",
                    "risk=${item.riskLevel}",
                    "source=${item.source}",
                ),
            )
            prevId = toolStepId
        }

        if (!context.preferSingleStep && selected.size > 1 && prevId != null) {
            val endId = "end-noop"
            val branchId = "s-branch"
            steps += PlanStep(
                stepId = branchId,
                kind = PlanStepKind.BRANCH,
                dependsOn = listOf(prevId),
                condition = PlanStepCondition(op = PlanConditionOp.ALWAYS),
                branchOnSuccess = endId,
                branchOnFailure = endId,
                cancelOnFailure = true,
                displaySummary = "End branch",
                notes = listOf("terminal_branch"),
            )
            steps += PlanStep(
                stepId = endId,
                kind = PlanStepKind.NOOP,
                displaySummary = "End",
                notes = listOf("terminal"),
            )
        }

        var plan = ActionPlan(
            planId = planId,
            goalId = goal.goalId,
            status = ActionPlanStatus.READY,
            steps = steps.toList(),
            entryStepId = steps.firstOrNull()?.stepId,
            resultSlots = steps
                .filter { it.kind == PlanStepKind.TOOL }
                .associate { it.stepId to "output" },
            notes = listOf(
                "planner=deterministic",
                "selected=${selected.size}",
                "preferSingle=${context.preferSingleStep}",
            ),
        )

        val validation = ActionPlanValidator.validate(plan)
        if (!validation.ok) {
            plan = plan.copy(
                status = ActionPlanStatus.INVALID,
                notes = plan.notes + validation.errors.map { "err=$it" },
            )
        }
        return plan
    }

    override fun cancel(plan: ActionPlan, reason: String): ActionPlan =
        plan.copy(
            status = ActionPlanStatus.CANCELLED,
            cancellationRequested = true,
            notes = plan.notes + "cancel=$reason".take(120),
        )

    private fun prevSuccess(prevId: String?): PlanStepCondition =
        if (prevId == null) {
            PlanStepCondition()
        } else {
            PlanStepCondition(
                op = PlanConditionOp.PREV_SUCCESS,
                dependsOnStepId = prevId,
            )
        }

    private fun boundedParams(item: DiscoveredItem): Map<String, String> =
        item.parameterNames
            .take(PlanStep.MAX_PARAMS)
            .associateWith { "" }
            .mapKeys { (k, _) -> k.take(PlanStep.MAX_PARAM_KEY) }
            .mapValues { (_, v) -> v.take(PlanStep.MAX_PARAM_VALUE) }
}
