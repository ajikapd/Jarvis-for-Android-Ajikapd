package com.jarvis.core.agent

import com.jarvis.core.autonomy.AutonomyActionPipeline
import com.jarvis.core.autonomy.AutonomyPlanStatus
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.autonomy.ProposedActionMapper
import com.jarvis.core.autonomy.SafeAutonomousExecutor
import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.PlanConditionOp
import com.jarvis.core.planner.PlanStep
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode

/**
 * Stage 12: Agent Execution Loop.
 *
 * ```
 * PLAN → EXECUTE STEP → RESULT → ANALYZE → CONTINUE / MODIFY / RETRY / STOP
 * ```
 *
 * Каждый TOOL-шаг снова проходит RiskClassifier → AutonomyPolicy → SafetyGate
 * через [AutonomyActionPipeline] (и опционально [SafeAutonomousExecutor] при live).
 *
 * Не Result Verification (Stage 13). Не переписывает Planner.
 */
class AgentExecutionLoop(
    private val pipeline: AutonomyActionPipeline = AutonomyActionPipeline(),
    private val toolExecutor: ToolExecutor? = null,
    private val safeExecutor: SafeAutonomousExecutor? = null,
) {

    fun run(plan: ActionPlan, context: AgentLoopContext = AgentLoopContext()): AgentLoopResult {
        if (plan.status == ActionPlanStatus.CANCELLED || plan.cancellationRequested) {
            return AgentLoopResult(
                planId = plan.planId,
                goalId = plan.goalId,
                completed = false,
                finalDecision = LoopDecision.STOP,
                stoppedReason = "plan_cancelled",
                records = emptyList(),
                resultSlots = emptyMap(),
                notes = listOf("cancelled_before_run"),
            )
        }
        if (plan.status != ActionPlanStatus.READY || plan.steps.isEmpty()) {
            return AgentLoopResult(
                planId = plan.planId,
                goalId = plan.goalId,
                completed = plan.status == ActionPlanStatus.EMPTY,
                finalDecision = LoopDecision.STOP,
                stoppedReason = "plan_not_ready:${plan.status}",
                records = emptyList(),
                resultSlots = emptyMap(),
                notes = plan.notes.take(4),
            )
        }

        val records = mutableListOf<StepRunRecord>()
        val stepStatus = linkedMapOf<String, StepRunStatus>()
        val resultSlots = linkedMapOf<String, String>()
        val attempts = mutableMapOf<String, Int>()
        val notes = mutableListOf("loop=stage12", "live=${context.live}")

        var index = 0
        val steps = plan.steps
        var finalDecision = LoopDecision.CONTINUE
        var stoppedReason = "completed"

        while (index < steps.size) {
            val step = steps[index]
            // Avoid re-entering already finished steps (branch jump / retries handled separately)
            val prior = stepStatus[step.stepId]
            if (prior == StepRunStatus.SUCCESS || prior == StepRunStatus.SKIPPED) {
                index++
                continue
            }

            val attempt = (attempts[step.stepId] ?: 0) + 1
            attempts[step.stepId] = attempt

            if (!depsSatisfied(step, stepStatus)) {
                records += StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.SKIPPED,
                    attempt = attempt,
                    message = "dependencies not satisfied",
                    decisionAfter = LoopDecision.CONTINUE,
                )
                stepStatus[step.stepId] = StepRunStatus.SKIPPED
                index++
                continue
            }

            if (!conditionHolds(step, stepStatus, resultSlots)) {
                records += StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.SKIPPED,
                    attempt = attempt,
                    message = "condition not met: ${step.condition.op}",
                    decisionAfter = LoopDecision.CONTINUE,
                )
                stepStatus[step.stepId] = StepRunStatus.SKIPPED
                index++
                continue
            }

            val outcome = executeStep(step, attempt, context, steps, index, stepStatus)
            records += outcome.record
            stepStatus[step.stepId] = outcome.record.status
            if (outcome.record.status == StepRunStatus.SUCCESS ||
                outcome.record.status == StepRunStatus.FAILURE
            ) {
                resultSlots[step.stepId] =
                    outcome.record.message.ifBlank { outcome.record.status.name }
            }

            when (outcome.record.decisionAfter) {
                LoopDecision.CONTINUE -> index = outcome.nextIndex
                LoopDecision.RETRY -> {
                    if (attempt >= step.retry.maxAttempts.coerceAtMost(context.maxRetriesPerStep)) {
                        finalDecision = LoopDecision.STOP
                        stoppedReason = "retry_exhausted:${step.stepId}"
                        notes += "retry_exhausted=${step.stepId}"
                        records[records.lastIndex] =
                            outcome.record.copy(decisionAfter = LoopDecision.STOP)
                        break
                    }
                    // stay on same index for retry
                }
                LoopDecision.MODIFY -> {
                    finalDecision = LoopDecision.MODIFY
                    stoppedReason = "modify_needed:${step.stepId}:${outcome.record.message}"
                    notes += "modify=${step.stepId}"
                    break
                }
                LoopDecision.STOP -> {
                    finalDecision = LoopDecision.STOP
                    stoppedReason = outcome.record.message.ifBlank { "stop:${step.stepId}" }
                    break
                }
            }
        }

        if (finalDecision == LoopDecision.CONTINUE && index >= steps.size) {
            val allOk = stepStatus.values.all {
                it == StepRunStatus.SUCCESS || it == StepRunStatus.SKIPPED
            }
            finalDecision = LoopDecision.STOP
            stoppedReason = if (allOk) "completed" else "finished_with_issues"
        }

        val reallyCompleted = stoppedReason == "completed" &&
            stepStatus.isNotEmpty() &&
            stepStatus.values.all {
                it == StepRunStatus.SUCCESS || it == StepRunStatus.SKIPPED
            }

        return AgentLoopResult(
            planId = plan.planId,
            goalId = plan.goalId,
            completed = reallyCompleted,
            finalDecision = finalDecision,
            stoppedReason = stoppedReason,
            records = records,
            resultSlots = resultSlots,
            notes = notes,
        )
    }

    private data class StepOutcome(val record: StepRunRecord, val nextIndex: Int)

    private fun executeStep(
        step: PlanStep,
        attempt: Int,
        context: AgentLoopContext,
        steps: List<PlanStep>,
        index: Int,
        stepStatus: Map<String, StepRunStatus>,
    ): StepOutcome = when (step.kind) {
        PlanStepKind.NOOP -> StepOutcome(
            StepRunRecord(
                stepId = step.stepId,
                status = StepRunStatus.SUCCESS,
                attempt = attempt,
                message = "noop",
                decisionAfter = LoopDecision.CONTINUE,
            ),
            index + 1,
        )
        PlanStepKind.WAIT -> StepOutcome(
            StepRunRecord(
                stepId = step.stepId,
                status = StepRunStatus.SUCCESS,
                attempt = attempt,
                message = "waited_logical:${step.waitMs}",
                decisionAfter = LoopDecision.CONTINUE,
                notes = listOf("no_thread_sleep"),
            ),
            index + 1,
        )
        PlanStepKind.CONFIRM -> {
            if (step.stepId in context.confirmedStepIds) {
                StepOutcome(
                    StepRunRecord(
                        stepId = step.stepId,
                        status = StepRunStatus.SUCCESS,
                        attempt = attempt,
                        message = "confirmed",
                        decisionAfter = LoopDecision.CONTINUE,
                    ),
                    index + 1,
                )
            } else {
                StepOutcome(
                    StepRunRecord(
                        stepId = step.stepId,
                        status = StepRunStatus.AWAITING_CONFIRMATION,
                        attempt = attempt,
                        message = "awaiting_confirmation",
                        decisionAfter = LoopDecision.STOP,
                    ),
                    index,
                )
            }
        }
        PlanStepKind.BRANCH -> {
            val prev = step.dependsOn.firstOrNull()
            val prevOk = prev != null && stepStatus[prev] == StepRunStatus.SUCCESS
            val targetId = if (prevOk) step.branchOnSuccess else step.branchOnFailure
            val targetIndex = targetId?.let { id -> steps.indexOfFirst { it.stepId == id } }
                ?.takeIf { it >= 0 }
                ?: (index + 1)
            StepOutcome(
                StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.SUCCESS,
                    attempt = attempt,
                    message = "branch→${targetId ?: "end"}",
                    decisionAfter = LoopDecision.CONTINUE,
                    notes = listOf("prevOk=$prevOk"),
                ),
                targetIndex,
            )
        }
        PlanStepKind.TOOL -> runToolStep(step, attempt, context, index)
    }

    private fun runToolStep(
        step: PlanStep,
        attempt: Int,
        context: AgentLoopContext,
        index: Int,
    ): StepOutcome {
        val toolId = step.toolId
        if (toolId.isNullOrBlank()) {
            return StepOutcome(
                StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.FAILURE,
                    attempt = attempt,
                    message = "missing_toolId",
                    decisionAfter = LoopDecision.MODIFY,
                ),
                index,
            )
        }

        val proposed = ProposedActionMapper.fromToolId(
            toolId = toolId,
            actionId = toolId,
            args = mergeDefaultArgs(toolId, step.parameters),
            displaySummary = step.displaySummary.ifBlank { null },
        )
        if (proposed == null) {
            return StepOutcome(
                StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.FAILURE,
                    attempt = attempt,
                    toolId = toolId,
                    message = "unknown_tool_mapping",
                    decisionAfter = LoopDecision.MODIFY,
                ),
                index,
            )
        }

        val decision = syntheticDecision(step)

        if (context.live && safeExecutor != null &&
            toolId in ProposedActionMapper.FIRST_SAFE_TOOL_IDS
        ) {
            return runLiveSafe(step, proposed, decision, attempt, context, index)
        }

        val autonomyPlan = pipeline.plan(
            decision = decision,
            proposed = proposed,
            level = context.autonomyLevel,
            allowlist = context.allowlist,
            blocklist = context.blocklist,
            nowMillis = context.nowMillis,
            grantedPermissions = context.grantedPermissions,
        )

        return when (autonomyPlan.status) {
            AutonomyPlanStatus.READY_TO_EXECUTE -> {
                if (context.live && toolExecutor != null) {
                    val liveOk = com.jarvis.core.nativeandroid.NativeAndroidToolCatalog.liveAllowed(toolId)
                    if (!liveOk) {
                        return StepOutcome(
                            StepRunRecord(
                                stepId = step.stepId,
                                status = StepRunStatus.BLOCKED,
                                attempt = attempt,
                                autonomyStatus = autonomyPlan.status,
                                toolId = toolId,
                                message = "live_blocked_stage14_mode",
                                decisionAfter = LoopDecision.STOP,
                            ),
                            index,
                        )
                    }
                    val call = autonomyPlan.draftToolCall
                        ?: ToolCall(
                            requestId = step.stepId,
                            toolId = toolId,
                            arguments = proposed.arguments.mapValues { it.value as Any },
                        )
                    val result = toolExecutor.execute(call)
                    val ok = result.status == ToolResultStatus.SUCCESS
                    toolOutcome(
                        step, attempt, toolId, ok,
                        result.message ?: result.status.name,
                        executed = true,
                        autonomyStatus = autonomyPlan.status,
                        index = index,
                    )
                } else {
                    StepOutcome(
                        StepRunRecord(
                            stepId = step.stepId,
                            status = StepRunStatus.SUCCESS,
                            attempt = attempt,
                            autonomyStatus = autonomyPlan.status,
                            toolId = toolId,
                            executed = false,
                            message = "dry_run_ready",
                            decisionAfter = LoopDecision.CONTINUE,
                            notes = autonomyPlan.notes.take(3),
                        ),
                        index + 1,
                    )
                }
            }
            AutonomyPlanStatus.AWAITING_CONFIRMATION,
            AutonomyPlanStatus.NEEDS_CONFIRMATION,
            -> StepOutcome(
                StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.AWAITING_CONFIRMATION,
                    attempt = attempt,
                    autonomyStatus = autonomyPlan.status,
                    toolId = toolId,
                    message = "gate_needs_confirmation",
                    decisionAfter = LoopDecision.STOP,
                    notes = autonomyPlan.notes.take(3),
                ),
                index,
            )
            AutonomyPlanStatus.DENIED -> {
                val canRetry = attempt < step.retry.maxAttempts && !step.cancelOnFailure
                StepOutcome(
                    StepRunRecord(
                        stepId = step.stepId,
                        status = StepRunStatus.BLOCKED,
                        attempt = attempt,
                        autonomyStatus = autonomyPlan.status,
                        toolId = toolId,
                        message = "denied:${autonomyPlan.verdict.reasonCode}",
                        decisionAfter = if (canRetry) LoopDecision.RETRY else LoopDecision.STOP,
                        notes = autonomyPlan.notes.take(3),
                    ),
                    index,
                )
            }
            AutonomyPlanStatus.NO_ACTION -> StepOutcome(
                StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.SKIPPED,
                    attempt = attempt,
                    autonomyStatus = autonomyPlan.status,
                    toolId = toolId,
                    message = "no_action",
                    decisionAfter = LoopDecision.CONTINUE,
                ),
                index + 1,
            )
        }
    }

    private fun runLiveSafe(
        step: PlanStep,
        proposed: ProposedAction,
        decision: ProactiveDecision,
        attempt: Int,
        context: AgentLoopContext,
        index: Int,
    ): StepOutcome {
        val result = safeExecutor!!.tryExecute(
            decision = decision,
            proposed = proposed,
            level = context.autonomyLevel,
            allowlist = context.allowlist,
            blocklist = context.blocklist,
            nowMillis = context.nowMillis,
        )
        return toolOutcome(
            step = step,
            attempt = attempt,
            toolId = proposed.toolId,
            ok = result.executed,
            message = result.reason,
            executed = result.executed,
            autonomyStatus = result.plan?.status,
            index = index,
        )
    }

    private fun toolOutcome(
        step: PlanStep,
        attempt: Int,
        toolId: String?,
        ok: Boolean,
        message: String,
        executed: Boolean,
        autonomyStatus: AutonomyPlanStatus?,
        index: Int,
    ): StepOutcome {
        if (ok) {
            return StepOutcome(
                StepRunRecord(
                    stepId = step.stepId,
                    status = StepRunStatus.SUCCESS,
                    attempt = attempt,
                    autonomyStatus = autonomyStatus,
                    toolId = toolId,
                    executed = executed,
                    message = message,
                    decisionAfter = LoopDecision.CONTINUE,
                ),
                index + 1,
            )
        }
        val decisionAfter = when {
            step.cancelOnFailure -> LoopDecision.STOP
            attempt < step.retry.maxAttempts -> LoopDecision.RETRY
            else -> LoopDecision.STOP
        }
        return StepOutcome(
            StepRunRecord(
                stepId = step.stepId,
                status = StepRunStatus.FAILURE,
                attempt = attempt,
                autonomyStatus = autonomyStatus,
                toolId = toolId,
                executed = executed,
                message = message,
                decisionAfter = decisionAfter,
            ),
            index,
        )
    }

    private fun depsSatisfied(step: PlanStep, status: Map<String, StepRunStatus>): Boolean {
        if (step.dependsOn.isEmpty()) return true
        return step.dependsOn.all { dep -> status[dep] == StepRunStatus.SUCCESS }
    }

    private fun conditionHolds(
        step: PlanStep,
        status: Map<String, StepRunStatus>,
        slots: Map<String, String>,
    ): Boolean {
        val c = step.condition
        return when (c.op) {
            PlanConditionOp.ALWAYS -> true
            PlanConditionOp.PREV_SUCCESS -> {
                val id = c.dependsOnStepId ?: return false
                status[id] == StepRunStatus.SUCCESS
            }
            PlanConditionOp.PREV_FAILURE -> {
                val id = c.dependsOnStepId ?: return false
                status[id] == StepRunStatus.FAILURE
            }
            PlanConditionOp.RESULT_EQUALS -> {
                val id = c.dependsOnStepId ?: return false
                slots[id] == c.expectedValue
            }
            PlanConditionOp.RESULT_CONTAINS -> {
                val id = c.dependsOnStepId ?: return false
                slots[id]?.contains(c.expectedValue.orEmpty()) == true
            }
        }
    }

    private fun syntheticDecision(step: PlanStep) = ProactiveDecision(
        type = ProactiveDecisionType.SUGGESTION,
        behaviorPatternKey = "agent.loop:${step.stepId}",
        reasonCode = ProactiveReasonCode.SELECTED,
        reason = "Stage 12 agent loop step ${step.stepId}",
        confidence = 0.85f,
        relevance = 0.85f,
    )

    /**
     * Подставляет безопасные default args для known tools, если план не заполнил literals.
     * Не обходит Gate — только делает ProposedAction валидным для argument rules.
     */
    private fun mergeDefaultArgs(toolId: String, params: Map<String, String>): Map<String, String> {
        val filled = params.filterValues { it.isNotBlank() }.toMutableMap()
        when (toolId) {
            "flashlight" -> filled.putIfAbsent("action", "on")
            "volume" -> filled.putIfAbsent("action", "up")
            "brightness" -> filled.putIfAbsent("action", "up")
            "music" -> filled.putIfAbsent("action", "play")
        }
        return filled
    }
}
