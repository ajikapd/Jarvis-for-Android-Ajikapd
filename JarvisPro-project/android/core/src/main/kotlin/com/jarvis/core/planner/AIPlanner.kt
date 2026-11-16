package com.jarvis.core.planner

/**
 * Stage 11: AI Planner — только создаёт [ActionPlan], не исполняет.
 *
 * Не RiskClassifier / AutonomyPolicy / SafetyGate / Executor.
 * Каждый шаг плана на Stage 12 снова пройдёт Risk → Policy → Gate.
 */
interface AIPlanner {
    fun plan(goal: UserGoal, context: PlannerContext): ActionPlan

    /** Пометить план отменённым до исполнения. */
    fun cancel(plan: ActionPlan, reason: String = "user_cancel"): ActionPlan
}
