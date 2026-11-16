package com.jarvis.core.workflow

import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.PlanConditionOp
import com.jarvis.core.planner.PlanStep
import com.jarvis.core.planner.PlanStepCondition
import com.jarvis.core.planner.PlanStepKind
import java.util.UUID

/**
 * Stage 20: визуальный workflow — тот же ActionPlan, другая проекция.
 */
enum class WorkflowNodeType {
    TRIGGER,
    ACTION,
    CONDITION,
    LOOP,
    END,
}

data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,
    val label: String,
    val toolId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val nextOnSuccess: String? = null,
    val nextOnFailure: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(label.length <= 120)
    }
}

data class WorkflowGraph(
    val id: String,
    val title: String,
    val entryNodeId: String,
    val nodes: List<WorkflowNode>,
) {
    init {
        require(nodes.isNotEmpty())
        require(nodes.any { it.id == entryNodeId })
        require(nodes.map { it.id }.toSet().size == nodes.size)
    }

    fun node(id: String): WorkflowNode? = nodes.firstOrNull { it.id == id }
}

/**
 * Конвертация ActionPlan ↔ WorkflowGraph (единый формат для AI и редактора).
 */
object WorkflowConverter {

    fun fromActionPlan(plan: ActionPlan, title: String = "Workflow"): WorkflowGraph {
        val nodes = mutableListOf<WorkflowNode>()
        val triggerId = "trigger"
        nodes += WorkflowNode(
            id = triggerId,
            type = WorkflowNodeType.TRIGGER,
            label = "Start",
            nextOnSuccess = plan.entryStepId,
        )
        for ((i, step) in plan.steps.withIndex()) {
            val next = plan.steps.getOrNull(i + 1)?.stepId
            val type = when (step.kind) {
                PlanStepKind.TOOL -> WorkflowNodeType.ACTION
                PlanStepKind.CONFIRM, PlanStepKind.BRANCH -> WorkflowNodeType.CONDITION
                PlanStepKind.WAIT -> WorkflowNodeType.LOOP
                PlanStepKind.NOOP -> WorkflowNodeType.END
            }
            nodes += WorkflowNode(
                id = step.stepId,
                type = type,
                label = step.displaySummary.ifBlank { step.toolId ?: step.kind.name },
                toolId = step.toolId,
                parameters = step.parameters,
                nextOnSuccess = step.branchOnSuccess ?: next,
                nextOnFailure = step.branchOnFailure,
            )
        }
        if (nodes.none { it.type == WorkflowNodeType.END }) {
            val endId = "end"
            nodes += WorkflowNode(id = endId, type = WorkflowNodeType.END, label = "End")
            // link last non-end to end if dangling
            val lastAction = nodes.lastOrNull { it.type != WorkflowNodeType.END && it.id != triggerId }
            if (lastAction != null && lastAction.nextOnSuccess == null) {
                val idx = nodes.indexOfFirst { it.id == lastAction.id }
                nodes[idx] = lastAction.copy(nextOnSuccess = endId)
            }
        }
        return WorkflowGraph(
            id = "wf-${plan.planId}",
            title = title,
            entryNodeId = triggerId,
            nodes = nodes,
        )
    }

    fun toActionPlan(graph: WorkflowGraph, goalId: String = "wf-goal"): ActionPlan {
        val steps = graph.nodes
            .filter { it.type != WorkflowNodeType.TRIGGER }
            .map { node ->
                val kind = when (node.type) {
                    WorkflowNodeType.ACTION -> PlanStepKind.TOOL
                    WorkflowNodeType.CONDITION -> PlanStepKind.BRANCH
                    WorkflowNodeType.LOOP -> PlanStepKind.WAIT
                    WorkflowNodeType.END -> PlanStepKind.NOOP
                    WorkflowNodeType.TRIGGER -> PlanStepKind.NOOP
                }
                when (kind) {
                    PlanStepKind.TOOL -> PlanStep(
                        stepId = node.id,
                        kind = kind,
                        toolId = node.toolId ?: "noop.tool",
                        parameters = node.parameters,
                        displaySummary = node.label,
                        branchOnSuccess = node.nextOnSuccess,
                        branchOnFailure = node.nextOnFailure,
                    )
                    PlanStepKind.WAIT -> PlanStep(
                        stepId = node.id,
                        kind = kind,
                        waitMs = 100L,
                        displaySummary = node.label,
                    )
                    PlanStepKind.BRANCH -> PlanStep(
                        stepId = node.id,
                        kind = kind,
                        displaySummary = node.label,
                        branchOnSuccess = node.nextOnSuccess ?: graph.nodes.last().id,
                        branchOnFailure = node.nextOnFailure ?: graph.nodes.last().id,
                        condition = PlanStepCondition(PlanConditionOp.ALWAYS),
                    )
                    else -> PlanStep(
                        stepId = node.id,
                        kind = PlanStepKind.NOOP,
                        displaySummary = node.label,
                    )
                }
            }
        val entry = graph.nodes.firstOrNull { it.id == graph.entryNodeId }?.nextOnSuccess
            ?: steps.firstOrNull()?.stepId
        return ActionPlan(
            planId = "plan-from-${graph.id}",
            goalId = goalId,
            status = if (steps.any { it.kind == PlanStepKind.TOOL }) {
                ActionPlanStatus.READY
            } else {
                ActionPlanStatus.EMPTY
            },
            steps = steps,
            entryStepId = entry,
            notes = listOf("from_workflow=${graph.id}"),
        )
    }
}
