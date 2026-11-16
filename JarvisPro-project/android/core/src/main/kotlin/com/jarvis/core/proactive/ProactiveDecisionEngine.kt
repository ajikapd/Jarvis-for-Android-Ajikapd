package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.companion.ContextSnapshot

/**
 * Stage 7D thin orchestrator:
 * candidate → filter → policy → [decision, nextState].
 *
 * Без ToolCall / Intent / UI / persistence / BehaviorStore writes.
 */
class ProactiveDecisionEngine(
    private val filter: ProactiveCandidateFilter = ProactiveCandidateFilter(),
    private val policy: DecisionPolicy = DecisionPolicy(),
    private val cooldownPolicy: CooldownPolicy = CooldownPolicy(),
    private val budget: ProactivityBudget = ProactivityBudget(),
) {

    /**
     * Чистая оценка. Не мутирует входной [state]; возвращает [DecisionEvaluationResult.nextState].
     */
    fun evaluate(
        candidate: BehaviorCandidate?,
        snapshot: ContextSnapshot,
        state: ProactivityState,
        feedback: BehaviorFeedbackView,
        nowMillis: Long,
    ): DecisionEvaluationResult {
        val now = ProactivityState.sanitizeNow(nowMillis)
        val refilled = budget.withRefill(state, now)

        if (candidate == null || candidate.patternKey.isBlank()) {
            val decision = ProactiveDecision(
                type = ProactiveDecisionType.NO_OP,
                reasonCode = ProactiveReasonCode.NO_CANDIDATE,
                reason = "No candidate",
                createdAtMillis = now,
            )
            return DecisionEvaluationResult(
                decision = decision,
                nextState = budget.record(refilled, now, ProactiveDecisionType.NO_OP),
            )
        }

        val filtered = filter.filter(candidate, snapshot, refilled, feedback, now)
        val decision = policy.decide(candidate, filtered, now)
        val nextState = applyDecision(refilled, decision, now)
        return DecisionEvaluationResult(decision = decision, nextState = nextState)
    }

    /**
     * Выбор лучшего среди нескольких кандидатов (один decision или NO_OP).
     *
     * Deterministic ranking среди eligible (без randomness / LLM / ML):
     * 1) eligibility (только eligible конкурируют за proactive)
     * 2) higher relevance
     * 3) higher confidence
     * 4) higher context match score (specificity)
     * 5) more matched signals
     * 6) stable tie-break: patternKey lexicographic
     *
     * Confidence сам по себе не побеждает low-relevance кандидата.
     */
    fun evaluateBest(
        candidates: List<BehaviorCandidate>,
        snapshot: ContextSnapshot,
        state: ProactivityState,
        feedback: BehaviorFeedbackView,
        nowMillis: Long,
    ): DecisionEvaluationResult {
        val now = ProactivityState.sanitizeNow(nowMillis)
        val refilled = budget.withRefill(state, now)
        if (candidates.isEmpty()) {
            return evaluate(null, snapshot, refilled, feedback, now)
        }

        var bestEligible: Pair<BehaviorCandidate, CandidateFilterResult>? = null
        var fallback: Pair<BehaviorCandidate, CandidateFilterResult>? = null

        for (c in candidates) {
            val f = filter.filter(c, snapshot, refilled, feedback, now)
            if (f.eligible) {
                if (bestEligible == null || isBetterEligible(c, f, bestEligible.first, bestEligible.second)) {
                    bestEligible = c to f
                }
            } else if (fallback == null || isBetterFallback(c, f, fallback.first, fallback.second)) {
                fallback = c to f
            }
        }

        val chosen = bestEligible ?: fallback
        if (chosen == null) {
            return evaluate(null, snapshot, refilled, feedback, now)
        }
        val decision = policy.decide(chosen.first, chosen.second, now)
        return DecisionEvaluationResult(
            decision = decision,
            nextState = applyDecision(refilled, decision, now),
        )
    }

    /** true если [a]/[fa] лучше текущего [b]/[fb] среди eligible. */
    private fun isBetterEligible(
        a: BehaviorCandidate,
        fa: CandidateFilterResult,
        b: BehaviorCandidate,
        fb: CandidateFilterResult,
    ): Boolean {
        val relA = fa.relevance?.relevance ?: 0f
        val relB = fb.relevance?.relevance ?: 0f
        val relCmp = relA.compareTo(relB)
        if (relCmp != 0) return relCmp > 0

        val confCmp = a.confidence.compareTo(b.confidence)
        if (confCmp != 0) return confCmp > 0

        val matchA = fa.match?.score ?: 0f
        val matchB = fb.match?.score ?: 0f
        val matchCmp = matchA.compareTo(matchB)
        if (matchCmp != 0) return matchCmp > 0

        val sigA = fa.match?.matchedSignals?.size ?: 0
        val sigB = fb.match?.matchedSignals?.size ?: 0
        val sigCmp = sigA.compareTo(sigB)
        if (sigCmp != 0) return sigCmp > 0

        return a.patternKey < b.patternKey
    }

    /** Для NO_OP fallback: предпочитаем более «объяснимый» reason, затем patternKey. */
    private fun isBetterFallback(
        a: BehaviorCandidate,
        fa: CandidateFilterResult,
        b: BehaviorCandidate,
        fb: CandidateFilterResult,
    ): Boolean {
        val prioA = fallbackReasonPriority(fa.reasonCode)
        val prioB = fallbackReasonPriority(fb.reasonCode)
        if (prioA != prioB) return prioA < prioB
        return a.patternKey < b.patternKey
    }

    private fun fallbackReasonPriority(code: ProactiveReasonCode): Int = when (code) {
        ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED -> 0
        ProactiveReasonCode.DISABLED -> 1
        ProactiveReasonCode.DUPLICATE -> 2
        ProactiveReasonCode.BUDGET_EXHAUSTED -> 3
        ProactiveReasonCode.GLOBAL_COOLDOWN,
        ProactiveReasonCode.BEHAVIOR_COOLDOWN,
        ProactiveReasonCode.FEEDBACK_COOLDOWN,
        -> 4
        ProactiveReasonCode.CONTEXT_MISMATCH -> 5
        ProactiveReasonCode.LOW_RELEVANCE -> 6
        ProactiveReasonCode.LOW_CONFIDENCE -> 7
        else -> 8
    }

    private fun applyDecision(
        state: ProactivityState,
        decision: ProactiveDecision,
        nowMillis: Long,
    ): ProactivityState {
        var next = budget.record(state, nowMillis, decision.type)
        if (decision.type != ProactiveDecisionType.NO_OP &&
            !decision.behaviorPatternKey.isNullOrBlank()
        ) {
            next = cooldownPolicy.afterProactiveDecision(
                state = next,
                nowMillis = nowMillis,
                patternKey = decision.behaviorPatternKey,
                type = decision.type,
                fingerprint = decision.fingerprint,
            )
        }
        return next
    }
}
