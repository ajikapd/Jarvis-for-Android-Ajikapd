package com.jarvis.core.runtime

/**
 * V2-01: явный runtime route для user request.
 * Не provider selection — это путь обработки запроса.
 */
enum class RouteDecision {
    /** Детерминированный skill / tool hot path. */
    FAST_SKILL,

    /** Диалог через DialogueResponder → LLM (cloud/local) или template. */
    CHAT,

    /** Planner → ActionPlan → AgentExecutionLoop. */
    PLAN,

    /** Нужно уточнение у пользователя. */
    CLARIFY,

    /** Детерминированный template (привет / время / identity / joke) — не LLM. */
    TEMPLATE,

    /** Неизвестный / неклассифицированный (не должен быть нормой). */
    UNKNOWN,
}

/**
 * Исход route decision с причиной (для telemetry).
 */
data class RouteDecisionResult(
    val route: RouteDecision,
    val reason: String,
    val skillId: String? = null,
    val toolId: String? = null,
    val templateReason: String? = null,
    val plannerUsed: Boolean = false,
    val agentUsed: Boolean = false,
)
