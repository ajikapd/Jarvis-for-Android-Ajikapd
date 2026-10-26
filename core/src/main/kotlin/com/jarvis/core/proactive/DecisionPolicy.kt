package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.behavior.PatternFamily

/**
 * Stage 7D: выбирает тип решения из explainable filter result.
 * NO_OP — нормальный и частый исход. Confidence ≠ relevance.
 */
class DecisionPolicy(
    private val thresholds: DecisionPolicyThresholds = DecisionPolicyThresholds(),
) {

    fun decide(
        candidate: BehaviorCandidate,
        filter: CandidateFilterResult,
        nowMillis: Long,
    ): ProactiveDecision {
        if (!filter.eligible) {
            return noOp(
                candidate = candidate,
                filter = filter,
                nowMillis = nowMillis,
                reasonCode = filter.reasonCode,
                reason = humanReason(filter.reasonCode),
            )
        }

        val relevance = filter.relevance?.relevance ?: 0f
        val confidence = candidate.confidence
        val notes = filter.notes.toMutableList()
        notes += "conf=$confidence"
        notes += "rel=$relevance"

        // high confidence + low relevance уже отсечён filter'ом, но перестраховка
        if (relevance < thresholds.minRelevanceEligible) {
            return noOp(candidate, filter, nowMillis, ProactiveReasonCode.LOW_RELEVANCE, "low_relevance", notes)
        }
        if (confidence < thresholds.minConfidenceEligible) {
            return noOp(candidate, filter, nowMillis, ProactiveReasonCode.LOW_CONFIDENCE, "low_confidence", notes)
        }

        val confirmationOk =
            confidence >= thresholds.minConfidenceConfirmation &&
                relevance >= thresholds.minRelevanceConfirmation &&
                isStrongActionCandidate(candidate)

        if (confirmationOk) {
            return ProactiveDecision(
                type = ProactiveDecisionType.CONFIRMATION_REQUEST,
                behaviorPatternKey = candidate.patternKey,
                behaviorId = candidate.id,
                fingerprint = filter.fingerprint,
                reasonCode = ProactiveReasonCode.SELECTED,
                reason = "Сильный контекстный match; нужно явное подтверждение (без выполнения).",
                confidence = confidence,
                relevance = relevance,
                createdAtMillis = nowMillis,
                notes = notes,
                metadata = mapOf("tier" to "confirmation"),
            )
        }

        val questionOk =
            relevance >= thresholds.minRelevanceQuestion &&
                confidence >= thresholds.minConfidenceQuestion &&
                confidence < thresholds.maxConfidenceQuestion &&
                !isStrongActionCandidate(candidate)

        if (questionOk) {
            return ProactiveDecision(
                type = ProactiveDecisionType.QUESTION,
                behaviorPatternKey = candidate.patternKey,
                behaviorId = candidate.id,
                fingerprint = filter.fingerprint,
                reasonCode = ProactiveReasonCode.SELECTED,
                reason = "Контекст похож, но уверенность умеренная — уточняющий вопрос.",
                confidence = confidence,
                relevance = relevance,
                createdAtMillis = nowMillis,
                notes = notes,
                metadata = mapOf("tier" to "question"),
            )
        }

        val suggestionOk =
            confidence >= thresholds.minConfidenceSuggestion &&
                relevance >= thresholds.minRelevanceSuggestion

        if (suggestionOk) {
            return ProactiveDecision(
                type = ProactiveDecisionType.SUGGESTION,
                behaviorPatternKey = candidate.patternKey,
                behaviorId = candidate.id,
                fingerprint = filter.fingerprint,
                reasonCode = ProactiveReasonCode.SELECTED,
                reason = "Уместное мягкое предложение по наблюдаемому поведению.",
                confidence = confidence,
                relevance = relevance,
                createdAtMillis = nowMillis,
                notes = notes,
                metadata = mapOf("tier" to "suggestion"),
            )
        }

        return noOp(
            candidate, filter, nowMillis,
            ProactiveReasonCode.LOW_RELEVANCE,
            "thresholds_not_met",
            notes,
        )
    }

    /** ROUTINE/SEQUENCE с trigger→follow — кандидаты на confirmation. */
    private fun isStrongActionCandidate(c: BehaviorCandidate): Boolean {
        val hasTriggerFollow =
            c.entities.containsKey("trigger") &&
                (c.entities.containsKey("follow") || c.entities.containsKey("to"))
        return hasTriggerFollow &&
            (c.kind == BehaviorKind.ROUTINE || c.family == PatternFamily.SEQUENCE)
    }

    private fun noOp(
        candidate: BehaviorCandidate,
        filter: CandidateFilterResult,
        nowMillis: Long,
        reasonCode: ProactiveReasonCode,
        reason: String,
        notes: List<String> = filter.notes,
    ): ProactiveDecision = ProactiveDecision(
        type = ProactiveDecisionType.NO_OP,
        behaviorPatternKey = candidate.patternKey,
        behaviorId = candidate.id,
        fingerprint = filter.fingerprint,
        reasonCode = reasonCode,
        reason = reason,
        confidence = candidate.confidence,
        relevance = filter.relevance?.relevance ?: 0f,
        createdAtMillis = nowMillis,
        cooldownUntilMillis = filter.cooldown?.blockedUntilMillis,
        notes = notes,
    )

    private fun humanReason(code: ProactiveReasonCode): String = when (code) {
        ProactiveReasonCode.DISABLED -> "Behavior disabled"
        ProactiveReasonCode.STALE -> "Behavior stale"
        ProactiveReasonCode.LOW_CONFIDENCE -> "Confidence too low"
        ProactiveReasonCode.LOW_RELEVANCE -> "Relevance too low"
        ProactiveReasonCode.CONTEXT_MISMATCH -> "Context mismatch"
        ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED -> "Already satisfied"
        ProactiveReasonCode.BUDGET_EXHAUSTED -> "Proactivity budget exhausted"
        ProactiveReasonCode.GLOBAL_COOLDOWN -> "Global cooldown"
        ProactiveReasonCode.BEHAVIOR_COOLDOWN -> "Behavior cooldown"
        ProactiveReasonCode.FEEDBACK_COOLDOWN -> "Feedback cooldown"
        ProactiveReasonCode.DUPLICATE -> "Duplicate fingerprint"
        ProactiveReasonCode.NO_CANDIDATE -> "No candidate"
        else -> code.name
    }
}
