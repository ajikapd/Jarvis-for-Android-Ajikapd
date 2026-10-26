package com.jarvis.core.proactive

/**
 * Stage 7 — допустимые типы решений. Только decision; без execution.
 */
enum class ProactiveDecisionType {
    NO_OP,
    SUGGESTION,
    QUESTION,
    CONFIRMATION_REQUEST,
}
