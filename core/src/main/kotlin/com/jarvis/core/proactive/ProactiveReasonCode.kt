package com.jarvis.core.proactive

/**
 * Объяснимые reason codes для diagnostics / DecisionPolicy (Stage 7+).
 * Пользователю не показываются напрямую.
 */
enum class ProactiveReasonCode {
    NO_CANDIDATE,
    LOW_CONFIDENCE,
    LOW_RELEVANCE,
    CONTEXT_MISMATCH,
    CONTEXT_ALREADY_SATISFIED,
    DISABLED,
    STALE,
    SENSITIVITY_BLOCKED,
    BEHAVIOR_COOLDOWN,
    FEEDBACK_COOLDOWN,
    GLOBAL_COOLDOWN,
    BUDGET_EXHAUSTED,
    DUPLICATE,
    ALREADY_HANDLED,
    SELECTED,
    /** Промежуточный: context/relevance слой ещё не решает NO_OP vs suggestion. */
    EVALUATED,
}
