package com.jarvis.core.autonomy

/**
 * Stage 8A: explainable reason codes для autonomy verdict.
 * Не показывать сырые коды в production UI (как Stage 7).
 */
enum class AutonomyReasonCode {
    NO_PROACTIVE_DECISION,
    PROACTIVE_NO_OP,
    NO_PROPOSED_ACTION,
    ACTION_NOT_ALLOWED_FOR_SOURCE,
    AUTONOMY_LEVEL_OFF,
    AUTONOMY_REQUIRES_CONFIRMATION,
    USER_BLOCKLISTED,
    NOT_IN_ALLOWLIST,
    RISK_CLASS_TOO_HIGH,
    CONFIDENCE_IRRELEVANT,
    CONTEXT_INSUFFICIENT,
    /** Stage 8C fail-closed: нет ActionRiskAssessment при наличии ProposedAction. */
    MISSING_RISK_ASSESSMENT,
    /** Stage 8C fail-closed: assessment не соответствует ProposedAction. */
    RISK_ASSESSMENT_MISMATCH,
    POLICY_ALLOW,
    POLICY_DENY,
    SAFETY_GATE_DENY,
    SAFETY_GATE_REQUIRE_CONFIRMATION,
    NOT_IMPLEMENTED,
}
