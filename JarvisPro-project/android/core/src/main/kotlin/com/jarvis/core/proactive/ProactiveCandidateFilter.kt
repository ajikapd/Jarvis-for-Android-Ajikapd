package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorCandidateStatus
import com.jarvis.core.companion.ContextSnapshot

/**
 * Stage 7D: отсев кандидатов до DecisionPolicy.
 * Explainable; использует A/B/C без side effects.
 */
class ProactiveCandidateFilter(
    private val matcher: ContextMatcher = ContextMatcher(),
    private val relevanceEvaluator: RelevanceEvaluator = RelevanceEvaluator(matcher = matcher),
    private val cooldownPolicy: CooldownPolicy = CooldownPolicy(),
    private val budget: ProactivityBudget = ProactivityBudget(),
    private val thresholds: DecisionPolicyThresholds = DecisionPolicyThresholds(),
) {

    fun filter(
        candidate: BehaviorCandidate,
        snapshot: ContextSnapshot,
        state: ProactivityState,
        feedback: BehaviorFeedbackView,
        nowMillis: Long,
    ): CandidateFilterResult {
        val notes = mutableListOf<String>()
        val fingerprint = buildFingerprint(candidate, snapshot)
        notes += "fp=$fingerprint"

        if (candidate.patternKey.isBlank()) {
            return ineligible(ProactiveReasonCode.NO_CANDIDATE, fingerprint, notes + "blank_key")
        }

        when (candidate.status) {
            BehaviorCandidateStatus.DISABLED ->
                return ineligible(ProactiveReasonCode.DISABLED, fingerprint, notes + "status_disabled")
            BehaviorCandidateStatus.STALE ->
                return ineligible(ProactiveReasonCode.STALE, fingerprint, notes + "status_stale")
            BehaviorCandidateStatus.REJECTED ->
                return ineligible(ProactiveReasonCode.FEEDBACK_COOLDOWN, fingerprint, notes + "status_rejected")
            else -> Unit
        }

        if (candidate.confidence < thresholds.minConfidenceEligible) {
            return ineligible(
                ProactiveReasonCode.LOW_CONFIDENCE,
                fingerprint,
                notes + "conf=${candidate.confidence}",
            )
        }

        val cooldown = cooldownPolicy.canConsider(
            patternKey = candidate.patternKey,
            nowMillis = nowMillis,
            state = state,
            feedback = feedback,
            fingerprint = fingerprint,
        )
        if (!cooldown.allowed) {
            return CandidateFilterResult(
                eligible = false,
                reasonCode = cooldown.reasonCode,
                fingerprint = fingerprint,
                cooldown = cooldown,
                notes = notes + cooldown.detail,
            )
        }

        val relevance = relevanceEvaluator.evaluate(candidate, snapshot)
        val match = relevance.match

        if (match.alreadySatisfied ||
            relevance.reasonCode == ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED
        ) {
            return CandidateFilterResult(
                eligible = false,
                reasonCode = ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED,
                fingerprint = fingerprint,
                match = match,
                relevance = relevance,
                cooldown = cooldown,
                notes = notes + relevance.notes,
            )
        }

        if (!match.matched) {
            return CandidateFilterResult(
                eligible = false,
                reasonCode = ProactiveReasonCode.CONTEXT_MISMATCH,
                fingerprint = fingerprint,
                match = match,
                relevance = relevance,
                cooldown = cooldown,
                notes = notes + relevance.notes,
            )
        }

        if (relevance.relevance < thresholds.minRelevanceEligible) {
            return CandidateFilterResult(
                eligible = false,
                reasonCode = ProactiveReasonCode.LOW_RELEVANCE,
                fingerprint = fingerprint,
                match = match,
                relevance = relevance,
                cooldown = cooldown,
                notes = notes + "rel=${relevance.relevance}",
            )
        }

        if (!budget.available(state, nowMillis)) {
            return CandidateFilterResult(
                eligible = false,
                reasonCode = ProactiveReasonCode.BUDGET_EXHAUSTED,
                fingerprint = fingerprint,
                match = match,
                relevance = relevance,
                cooldown = cooldown,
                notes = notes + "budget=0",
            )
        }

        return CandidateFilterResult(
            eligible = true,
            reasonCode = ProactiveReasonCode.EVALUATED,
            fingerprint = fingerprint,
            match = match,
            relevance = relevance,
            cooldown = cooldown,
            notes = notes + relevance.notes,
        )
    }

    /**
     * Deterministic bounded fingerprint — без raw text / secrets.
     */
    fun buildFingerprint(candidate: BehaviorCandidate, snapshot: ContextSnapshot): String {
        val ctx = snapshot.context
        val hourBucket = (snapshot.takenAtMillis / 3_600_000L) // hour epoch bucket
        val appToken = ctx.currentApp?.let { shortPkg(it) } ?: "-"
        val hp = if (ctx.headphonesConnected) "1" else "0"
        return listOf(
            candidate.patternKey.take(96),
            "hp=$hp",
            "app=$appToken",
            "h=$hourBucket",
        ).joinToString("|")
    }

    private fun shortPkg(pkg: String): String {
        val parts = pkg.split('.')
        return parts.takeLast(2).joinToString(".").take(40)
    }

    private fun ineligible(
        code: ProactiveReasonCode,
        fingerprint: String,
        notes: List<String>,
    ): CandidateFilterResult = CandidateFilterResult(
        eligible = false,
        reasonCode = code,
        fingerprint = fingerprint,
        notes = notes,
    )
}
