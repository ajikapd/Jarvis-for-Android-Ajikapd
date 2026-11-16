package com.jarvis.core.autonomy

import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.proactive.ProactiveDecision

/**
 * Stage 8F: исполнение **только** first-safe actions после READY_TO_EXECUTE.
 *
 * Разрешённые toolId: [ProposedActionMapper.FIRST_SAFE_TOOL_IDS]
 * (flashlight / volume / brightness).
 *
 * Не обходит AutonomyPolicy / ActionSafetyGate.
 * Не исполняет SMS / calls / UI automation / financial.
 */
class SafeAutonomousExecutor(
    private val toolExecutor: ToolExecutor,
    private val pipeline: AutonomyActionPipeline,
) {

    fun tryExecute(
        decision: ProactiveDecision,
        proposed: ProposedAction,
        level: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
        allowlist: Set<String> = emptySet(),
        blocklist: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
    ): SafeAutonomousResult {
        val toolId = proposed.toolId
        if (toolId == null || toolId !in ProposedActionMapper.FIRST_SAFE_TOOL_IDS) {
            return SafeAutonomousResult(
                executed = false,
                plan = null,
                result = null,
                reason = "tool not in FIRST_SAFE_TOOL_IDS: ${toolId ?: "null"}",
            )
        }

        val plan = pipeline.plan(
            decision = decision,
            proposed = proposed,
            level = level,
            allowlist = allowlist,
            blocklist = blocklist,
            nowMillis = nowMillis,
        )

        if (plan.status != AutonomyPlanStatus.READY_TO_EXECUTE) {
            return SafeAutonomousResult(
                executed = false,
                plan = plan,
                result = null,
                reason = "plan status=${plan.status}",
            )
        }

        val call = plan.draftToolCall
            ?: return SafeAutonomousResult(
                executed = false,
                plan = plan,
                result = null,
                reason = "no draft ToolCall",
            )

        val result = toolExecutor.execute(call)
        AutonomyAuditLog.record(
            plan.verdict.copy(
                notes = plan.verdict.notes +
                    "exec=${result.status}" +
                    "tool=${result.toolId}",
            ),
            stage = "safe.exec",
        )
        return SafeAutonomousResult(
            executed = result.status == ToolResultStatus.SUCCESS,
            plan = plan,
            result = result,
            reason = result.message ?: result.status.name,
            toolCall = call,
        )
    }
}

data class SafeAutonomousResult(
    val executed: Boolean,
    val plan: AutonomyActionPlan?,
    val result: ToolResult?,
    val reason: String,
    val toolCall: ToolCall? = null,
)
