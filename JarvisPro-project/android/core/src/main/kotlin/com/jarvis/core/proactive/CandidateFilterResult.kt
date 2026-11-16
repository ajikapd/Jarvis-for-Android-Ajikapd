package com.jarvis.core.proactive

/**
 * Пороги DecisionPolicy — deterministic, настраиваемые, не ML.
 */
data class DecisionPolicyThresholds(
    val minConfidenceEligible: Float = 0.45f,
    val minRelevanceEligible: Float = 0.40f,
    val minConfidenceSuggestion: Float = 0.55f,
    val minRelevanceSuggestion: Float = 0.55f,
    val minConfidenceQuestion: Float = 0.48f,
    val minRelevanceQuestion: Float = 0.62f,
    val maxConfidenceQuestion: Float = 0.72f,
    val minConfidenceConfirmation: Float = 0.70f,
    val minRelevanceConfirmation: Float = 0.68f,
)

/**
 * Explainable filter output (не Boolean).
 */
data class CandidateFilterResult(
    val eligible: Boolean,
    val reasonCode: ProactiveReasonCode,
    val fingerprint: String,
    val match: ContextMatchResult? = null,
    val relevance: RelevanceResult? = null,
    val cooldown: CooldownCheckResult? = null,
    val notes: List<String> = emptyList(),
)
