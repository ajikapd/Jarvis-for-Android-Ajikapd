package com.jarvis.core.runtime

import com.jarvis.core.agent.AgentExecutionLoop
import com.jarvis.core.agent.AgentLoopContext
import com.jarvis.core.agent.AgentLoopResult
import com.jarvis.core.discovery.DiscoveryResult
import com.jarvis.core.planner.AIPlanner
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.DeterministicAIPlanner
import com.jarvis.core.planner.PlannerContext
import com.jarvis.core.planner.UserGoal

/**
 * V2-01: явная runtime boundary для PLAN path.
 *
 * Полный semantic router — V2-02. Здесь только:
 * goal → [AIPlanner] → ActionPlan → [AgentExecutionLoop]
 * (каждый TOOL step снова Risk→Policy→Gate).
 */
class PlanPathCoordinator(
    private val planner: AIPlanner = DeterministicAIPlanner(),
    private val agentLoop: AgentExecutionLoop = AgentExecutionLoop(),
) {

    data class PlanRun(
        val goal: UserGoal,
        val plan: ActionPlan,
        val loopResult: AgentLoopResult?,
        val dryRun: Boolean,
    )

    /**
     * Строит план. По умолчанию dry-run loop (live=false).
     * Live tool steps только если [live]=true и loop настроен с SafeAutonomousExecutor.
     */
    fun planAndRun(
        goalText: String,
        discovery: DiscoveryResult,
        requestId: String,
        nowMillis: Long = System.currentTimeMillis(),
        live: Boolean = false,
        maxSteps: Int = 6,
    ): PlanRun {
        val goal = UserGoal(
            goalId = "$requestId/goal",
            text = goalText.take(UserGoal.MAX_TEXT),
            createdAtMillis = nowMillis,
        )
        val plan = planner.plan(
            goal,
            PlannerContext(
                discovery = discovery,
                preferSingleStep = false,
                maxSteps = maxSteps,
            ),
        )
        val loopResult = agentLoop.run(
            plan,
            AgentLoopContext(
                live = live,
                nowMillis = nowMillis,
            ),
        )
        return PlanRun(
            goal = goal,
            plan = plan,
            loopResult = loopResult,
            dryRun = !live,
        )
    }
}
