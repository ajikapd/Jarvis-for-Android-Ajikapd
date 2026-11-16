package com.jarvis.core.proactive

/**
 * Stage 7C: deterministic token budget.
 * Высокая confidence не влияет — только record/available/refill.
 */
class ProactivityBudget(
    private val config: ProactivityConfig = ProactivityConfig.DEFAULT,
) {

    fun available(state: ProactivityState, nowMillis: Long): Boolean =
        remaining(state, nowMillis) >= minCost()

    fun remaining(state: ProactivityState, nowMillis: Long): Int {
        val refilled = withRefill(state, nowMillis)
        return refilled.budgetTokens.coerceIn(0, config.budgetMaxTokens)
    }

    /** Пополнить токены по elapsed intervals; immutable. */
    fun withRefill(state: ProactivityState, nowMillis: Long): ProactivityState {
        val now = ProactivityState.sanitizeNow(nowMillis)
        var tokens = state.budgetTokens.coerceIn(0, config.budgetMaxTokens)
        var last = state.budgetLastRefillAtMillis

        if (last <= 0L) {
            return state.copy(
                budgetTokens = tokens.coerceAtLeast(0).coerceAtMost(config.budgetMaxTokens),
                budgetLastRefillAtMillis = now,
            )
        }

        // Time rollback: не начисляем токены, не откатываем last назад
        if (now < last) {
            return state.copy(budgetTokens = tokens)
        }

        val elapsed = now - last
        val intervals = (elapsed / config.budgetRefillIntervalMs).toInt()
        if (intervals > 0) {
            tokens = (tokens + intervals).coerceAtMost(config.budgetMaxTokens)
            last += intervals * config.budgetRefillIntervalMs
        }
        return state.copy(budgetTokens = tokens, budgetLastRefillAtMillis = last)
    }

    /**
     * Списать budget за non-NO_OP decision.
     * NO_OP не тратит токены.
     * Если не хватает — state без изменений (caller решит NO_OP / BUDGET_EXHAUSTED).
     */
    fun record(
        state: ProactivityState,
        nowMillis: Long,
        type: ProactiveDecisionType,
    ): ProactivityState {
        val now = ProactivityState.sanitizeNow(nowMillis)
        var s = withRefill(state, now)
        if (type == ProactiveDecisionType.NO_OP) {
            return s.copy(recordedNoOpCount = (s.recordedNoOpCount + 1).coerceAtMost(1_000_000))
        }
        val cost = costOf(type)
        if (s.budgetTokens < cost) return s

        s = s.copy(budgetTokens = s.budgetTokens - cost)
        return when (type) {
            ProactiveDecisionType.SUGGESTION ->
                s.copy(suggestionCount = (s.suggestionCount + 1).coerceAtMost(1_000_000))
            ProactiveDecisionType.QUESTION ->
                s.copy(questionCount = (s.questionCount + 1).coerceAtMost(1_000_000))
            ProactiveDecisionType.CONFIRMATION_REQUEST ->
                s.copy(confirmationCount = (s.confirmationCount + 1).coerceAtMost(1_000_000))
            ProactiveDecisionType.NO_OP -> s
        }
    }

    fun costOf(type: ProactiveDecisionType): Int = when (type) {
        ProactiveDecisionType.NO_OP -> 0
        ProactiveDecisionType.SUGGESTION -> config.budgetCostSuggestion
        ProactiveDecisionType.QUESTION -> config.budgetCostQuestion
        ProactiveDecisionType.CONFIRMATION_REQUEST -> config.budgetCostConfirmation
    }

    private fun minCost(): Int = minOf(
        config.budgetCostSuggestion,
        config.budgetCostQuestion,
        config.budgetCostConfirmation,
    )
}
