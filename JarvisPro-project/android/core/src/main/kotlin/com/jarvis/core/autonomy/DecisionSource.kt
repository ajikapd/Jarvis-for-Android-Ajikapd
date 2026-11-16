package com.jarvis.core.autonomy

/**
 * Stage 8A: откуда пришёл запрос на действие.
 * Proactive path ≠ voice/utterance path.
 */
enum class DecisionSource {
    PROACTIVE,
    USER_UTTERANCE,
    MANUAL_UI,
    SYSTEM,
}
