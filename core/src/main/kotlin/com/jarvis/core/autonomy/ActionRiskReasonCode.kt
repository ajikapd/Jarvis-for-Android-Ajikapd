package com.jarvis.core.autonomy

/**
 * Stage 8B: детерминированные причины классификации риска.
 * Не для production user UI как сырые коды.
 */
enum class ActionRiskReasonCode {
    NO_SIDE_EFFECT,
    READ_ONLY,
    LOCAL_REVERSIBLE,
    USER_VISIBLE_EFFECT,
    DIRECT_STATE_CHANGE,
    EXTERNAL_SIDE_EFFECT,
    DATA_MUTATION,
    DATA_DELETION,
    FINANCIAL_EFFECT,
    IRREVERSIBLE_ACTION,
    UNKNOWN_ACTION,
}
