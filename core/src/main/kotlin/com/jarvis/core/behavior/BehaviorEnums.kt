package com.jarvis.core.behavior

/**
 * Stage 6: Behavior ≠ Memory ≠ Routine.
 * Memory = факты о пользователе; Behavior = наблюдаемая закономерность;
 * Routine = устойчивый повторяющийся сценарий.
 */
enum class BehaviorKind {
    /** Общая наблюдаемая закономерность. */
    PATTERN,
    /** Устойчивая повторяющаяся последовательность/сценарий. */
    ROUTINE,
}

enum class PatternFamily {
    TEMPORAL,
    SEQUENCE,
    FREQUENCY,
    CO_OCCURRENCE,
    CONTEXT,
}

enum class BehaviorCandidateStatus {
    SUGGEST_ONLY,
    ACCEPTED,
    IGNORED,
    REJECTED,
    DISABLED,
    STALE,
}

enum class BehaviorFeedback {
    ACCEPT,
    IGNORE,
    REJECT,
    DISABLE,
}
