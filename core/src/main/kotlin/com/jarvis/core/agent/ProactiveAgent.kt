package com.jarvis.core.agent

import com.jarvis.core.autonomy.AutonomyActionPipeline
import com.jarvis.core.autonomy.AutonomyLevel
import com.jarvis.core.autonomy.DefaultActionSafetyGate
import com.jarvis.core.autonomy.ProposedActionMapper
import com.jarvis.core.discovery.DiscoveryResult
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.DeterministicAIPlanner
import com.jarvis.core.planner.PlanStep
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.planner.PlannerContext
import com.jarvis.core.planner.UserGoal
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import java.util.UUID

/**
 * Stage 25: Proactive Agent — связка Context/Behavior/Routine/Memory + Agent layer.
 * Предложения проходят AutonomyPolicy / SafetyGate; silent dangerous exec запрещён.
 */
data class ProactiveSuggestion(
    val suggestionId: String,
    val decision: ProactiveDecision,
    val plan: ActionPlan?,
    val allowedToPropose: Boolean,
    val reason: String,
)

class ProactiveAgent(
    private val planner: DeterministicAIPlanner = DeterministicAIPlanner(),
    private val loop: AgentExecutionLoop = AgentExecutionLoop(
        pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate()),
    ),
) {
    /**
     * Из proactive decision + discovery → suggestion (не silent exec).
     */
    fun suggest(
        decision: ProactiveDecision,
        discovery: DiscoveryResult,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProactiveSuggestion {
        if (decision.type == ProactiveDecisionType.NO_OP) {
            return ProactiveSuggestion(
                suggestionId = "sug-${UUID.randomUUID()}",
                decision = decision,
                plan = null,
                allowedToPropose = false,
                reason = "no_op",
            )
        }
        val text = decision.behaviorPatternKey ?: decision.reason.ifBlank { "proactive" }
        val goal = UserGoal("proactive-${UUID.randomUUID()}", text.take(200), nowMillis)
        val plan = planner.plan(goal, PlannerContext(discovery = discovery, preferSingleStep = true))
        val ok = plan.status == ActionPlanStatus.READY && plan.steps.isNotEmpty()
        return ProactiveSuggestion(
            suggestionId = "sug-${UUID.randomUUID()}",
            decision = decision,
            plan = plan,
            allowedToPropose = ok,
            reason = if (ok) "ready_to_propose" else "no_plan",
        )
    }

    /**
     * Dry-run исполнение через AgentExecutionLoop (live=false) — только после user/policy path.
     */
    fun dryRun(
        suggestion: ProactiveSuggestion,
        level: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
        nowMillis: Long = System.currentTimeMillis(),
    ): AgentLoopResult? {
        val plan = suggestion.plan ?: return null
        if (!suggestion.allowedToPropose) return null
        return loop.run(
            plan,
            AgentLoopContext(live = false, autonomyLevel = level, nowMillis = nowMillis),
        )
    }

    /** Map first-safe tool from pattern for suggestion plan when discovery empty. */
    fun planFromMappedTool(
        toolId: String,
        args: Map<String, String> = emptyMap(),
    ): ActionPlan? {
        val proposed = ProposedActionMapper.fromToolId(toolId, args = args) ?: return null
        return ActionPlan(
            planId = "proactive-tool-${UUID.randomUUID()}",
            goalId = "g-${toolId}",
            status = ActionPlanStatus.READY,
            steps = listOf(
                PlanStep(
                    stepId = "s1",
                    kind = PlanStepKind.TOOL,
                    toolId = proposed.toolId,
                    parameters = proposed.arguments,
                    displaySummary = proposed.displaySummary,
                ),
            ),
        )
    }
}
