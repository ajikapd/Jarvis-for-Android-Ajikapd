package com.jarvis.core.agent

import com.jarvis.core.autonomy.AutonomyLevel
import com.jarvis.core.autonomy.AutonomyPlanStatus
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus

/**
 * Исход одного шага в Agent Execution Loop (Stage 12).
 */
enum class StepRunStatus {
    SUCCESS,
    FAILURE,
    SKIPPED,
    BLOCKED,
    AWAITING_CONFIRMATION,
    CANCELLED,
}

/**
 * Решение после анализа результата шага.
 */
enum class LoopDecision {
    CONTINUE,
    RETRY,
    MODIFY,
    STOP,
}

data class StepRunRecord(
    val stepId: String,
    val status: StepRunStatus,
    val attempt: Int = 1,
    val autonomyStatus: AutonomyPlanStatus? = null,
    val toolId: String? = null,
    val executed: Boolean = false,
    val message: String = "",
    val decisionAfter: LoopDecision,
    val notes: List<String> = emptyList(),
)

data class AgentLoopContext(
    /** false = gate+analyze без side-effect execute (default safe). */
    val live: Boolean = false,
    val autonomyLevel: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
    /** Step ids пользователя, уже подтверждённые (CONFIRM steps). */
    val confirmedStepIds: Set<String> = emptySet(),
    val allowlist: Set<String> = emptySet(),
    val blocklist: Set<String> = emptySet(),
    val maxRetriesPerStep: Int = 3,
    val nowMillis: Long = System.currentTimeMillis(),
    val grantedPermissions: Set<String> = emptySet(),
) {
    init {
        require(maxRetriesPerStep in 1..5)
    }
}

data class AgentLoopResult(
    val planId: String,
    val goalId: String,
    val completed: Boolean,
    val finalDecision: LoopDecision,
    val stoppedReason: String,
    val records: List<StepRunRecord>,
    /** Заполненные слоты результатов (stepId → status/message). */
    val resultSlots: Map<String, String>,
    val notes: List<String> = emptyList(),
)

internal fun ActionPlan.isRunnable(): Boolean =
    status == ActionPlanStatus.READY && !cancellationRequested && steps.isNotEmpty()
