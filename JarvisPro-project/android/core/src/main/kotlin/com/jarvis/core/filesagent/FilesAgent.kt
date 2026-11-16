package com.jarvis.core.filesagent

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
 * Stage 17: файловые операции Files Agent.
 * Опасные (delete/move/…) помечаются confirmation + высокий risk hint.
 */
enum class FileOperation {
    SEARCH,
    INSPECT,
    READ,
    CREATE,
    RENAME,
    MOVE,
    ORGANIZE,
    DELETE,
    METADATA,
}

data class FileAgentRequest(
    val goal: UserGoal,
    val operation: FileOperation,
    val path: String? = null,
    val query: String? = null,
    val destination: String? = null,
    val contentHint: String? = null,
) {
    init {
        path?.let { require(it.length <= MAX_PATH) }
        query?.let { require(it.length <= MAX_QUERY) }
        destination?.let { require(it.length <= MAX_PATH) }
    }

    companion object {
        const val MAX_PATH = 512
        const val MAX_QUERY = 200
    }
}

enum class FileOpRiskHint {
    SAFE_READ,
    MUTATING,
    DESTRUCTIVE,
}

object FileOperationPolicy {
    fun riskHint(op: FileOperation): FileOpRiskHint = when (op) {
        FileOperation.SEARCH,
        FileOperation.INSPECT,
        FileOperation.READ,
        FileOperation.METADATA,
        -> FileOpRiskHint.SAFE_READ
        FileOperation.CREATE,
        FileOperation.RENAME,
        FileOperation.MOVE,
        FileOperation.ORGANIZE,
        -> FileOpRiskHint.MUTATING
        FileOperation.DELETE -> FileOpRiskHint.DESTRUCTIVE
    }

    fun riskLevel(op: FileOperation): RiskLevel = when (riskHint(op)) {
        FileOpRiskHint.SAFE_READ -> RiskLevel.LOW
        FileOpRiskHint.MUTATING -> RiskLevel.CONFIRM
        FileOpRiskHint.DESTRUCTIVE -> RiskLevel.SENSITIVE
    }

    fun requiresConfirmation(op: FileOperation): Boolean =
        riskHint(op) != FileOpRiskHint.SAFE_READ

    /** Mapped tool id available today (Stage 14/17). Others stay planned stubs. */
    fun toolIdFor(op: FileOperation): String = when (op) {
        FileOperation.SEARCH -> "files.search"
        else -> "files.${op.name.lowercase()}"
    }
}

/**
 * Stage 17: Files Agent — строит [ActionPlan] для файловых целей.
 * Не исполняет FS сам; опасные ops → CONFIRM + Risk/Policy на Stage 12.
 */
class FilesAgent {

    fun plan(request: FileAgentRequest): ActionPlan {
        val planId = "files-${UUID.randomUUID()}"
        val op = request.operation
        val needsConfirm = FileOperationPolicy.requiresConfirmation(op)
        val steps = mutableListOf<PlanStep>()
        var prev: String? = null

        if (needsConfirm) {
            val confirmId = "files-confirm"
            steps += PlanStep(
                stepId = confirmId,
                kind = PlanStepKind.CONFIRM,
                requiresConfirmation = true,
                displaySummary = "Confirm file ${op.name.lowercase()}",
                notes = listOf(
                    "risk=${FileOperationPolicy.riskLevel(op)}",
                    "hint=${FileOperationPolicy.riskHint(op)}",
                ),
            )
            prev = confirmId
        }

        val toolId = FileOperationPolicy.toolIdFor(op)
        val params = linkedMapOf<String, String>()
        request.query?.takeIf { it.isNotBlank() }?.let { params["query"] = it }
        request.path?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
        request.destination?.takeIf { it.isNotBlank() }?.let { params["destination"] = it }
        request.contentHint?.takeIf { it.isNotBlank() }?.let { params["content"] = it.take(PlanStep.MAX_PARAM_VALUE) }
        if (op == FileOperation.SEARCH && params["query"].isNullOrBlank()) {
            // fallback: use goal text as query
            params["query"] = request.goal.text.take(FileAgentRequest.MAX_QUERY)
        }

        val toolStepId = "files-op"
        steps += PlanStep(
            stepId = toolStepId,
            kind = PlanStepKind.TOOL,
            toolId = toolId,
            parameters = params,
            dependsOn = prev?.let { listOf(it) }.orEmpty(),
            condition = if (prev != null) {
                PlanStepCondition(PlanConditionOp.PREV_SUCCESS, dependsOnStepId = prev)
            } else {
                PlanStepCondition()
            },
            requiresConfirmation = needsConfirm,
            retry = PlanRetryPolicy(maxAttempts = if (op == FileOperation.SEARCH) 2 else 1),
            cancelOnFailure = needsConfirm,
            displaySummary = "${op.name}: ${request.path ?: request.query ?: request.goal.text.take(40)}",
            notes = listOf(
                "files_agent=true",
                "operation=$op",
                "risk=${FileOperationPolicy.riskLevel(op)}",
            ),
        )

        var plan = ActionPlan(
            planId = planId,
            goalId = request.goal.goalId,
            status = ActionPlanStatus.READY,
            steps = steps,
            entryStepId = steps.first().stepId,
            resultSlots = mapOf(toolStepId to "output"),
            notes = listOf("planner=files_agent", "op=$op"),
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

    /** Эвристика: распознать операцию из текста цели (ru/en keywords). */
    fun inferOperation(text: String): FileOperation {
        val t = text.lowercase()
        return when {
            listOf("удали", "delete", "стереть", "remove").any { it in t } -> FileOperation.DELETE
            listOf("переимен", "rename").any { it in t } -> FileOperation.RENAME
            listOf("перемест", "move", "перенеси").any { it in t } -> FileOperation.MOVE
            listOf("создай", "create", "новый файл").any { it in t } -> FileOperation.CREATE
            listOf("организ", "разложи", "organize").any { it in t } -> FileOperation.ORGANIZE
            listOf("метадан", "metadata", "свойств").any { it in t } -> FileOperation.METADATA
            listOf("прочитай", "read", "открой файл", "содержим").any { it in t } -> FileOperation.READ
            listOf("inspect", "проверь файл", "осмотр").any { it in t } -> FileOperation.INSPECT
            else -> FileOperation.SEARCH
        }
    }

    fun planFromGoal(goal: UserGoal, path: String? = null, query: String? = null): ActionPlan =
        plan(
            FileAgentRequest(
                goal = goal,
                operation = inferOperation(goal.text),
                path = path,
                query = query,
            ),
        )
}
