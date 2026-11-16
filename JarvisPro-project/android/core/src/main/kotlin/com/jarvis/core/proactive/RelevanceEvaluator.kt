package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorCandidateStatus
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.companion.ContextSnapshot
import kotlin.math.exp

/**
 * Stage 7 B: relevance ≠ confidence.
 * Deterministic scoring; веса настраиваемые через [RelevanceWeights].
 */
class RelevanceEvaluator(
    private val matcher: ContextMatcher = ContextMatcher(),
    private val weights: RelevanceWeights = RelevanceWeights(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun evaluate(candidate: BehaviorCandidate, snapshot: ContextSnapshot): RelevanceResult {
        val match = matcher.match(candidate, snapshot)
        val notes = mutableListOf<String>()

        if (match.alreadySatisfied) {
            notes += "already_satisfied"
            return RelevanceResult(
                relevance = 0f,
                match = match,
                reasonCode = ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED,
                notes = notes,
            )
        }

        if (candidate.status == BehaviorCandidateStatus.DISABLED ||
            candidate.status == BehaviorCandidateStatus.STALE
        ) {
            notes += "status=${candidate.status}"
            return RelevanceResult(
                relevance = 0f,
                match = match.copy(matched = false),
                reasonCode = if (candidate.status == BehaviorCandidateStatus.DISABLED) {
                    ProactiveReasonCode.DISABLED
                } else {
                    ProactiveReasonCode.STALE
                },
                notes = notes,
            )
        }

        val w = weights.normalized()
        val contextPart = match.score
        val recencyPart = recencyFactor(candidate.lastObservedMillis, snapshot.takenAtMillis)
        val statusPart = statusFactor(candidate)
        val kindPart = kindFactor(candidate)

        var relevance = (
            w.contextMatch * contextPart +
                w.recency * recencyPart +
                w.statusBoost * statusPart +
                w.kindBoost * kindPart
            ).coerceIn(0f, 1f)

        if (!match.matched) {
            relevance = (relevance * 0.35f).coerceIn(0f, 0.45f)
            notes += "context_not_matched"
        }

        notes += "ctx=${"%.2f".format(contextPart)}"
        notes += "rec=${"%.2f".format(recencyPart)}"
        notes += match.matchedSignals.map { "m:$it" }
        notes += match.mismatchSignals.map { "x:$it" }

        val reason = when {
            !match.matched && match.score < 0.25f -> ProactiveReasonCode.CONTEXT_MISMATCH
            relevance < 0.35f -> ProactiveReasonCode.LOW_RELEVANCE
            else -> ProactiveReasonCode.EVALUATED
        }

        return RelevanceResult(
            relevance = relevance,
            match = match,
            reasonCode = reason,
            notes = notes,
        )
    }

    private fun recencyFactor(lastObservedMillis: Long, nowMillis: Long): Float {
        val days = ((nowMillis - lastObservedMillis).coerceAtLeast(0).toDouble() / 86_400_000.0)
        return exp(-days / 14.0).toFloat().coerceIn(0f, 1f)
    }

    private fun statusFactor(c: BehaviorCandidate): Float = when (c.status) {
        BehaviorCandidateStatus.SUGGEST_ONLY -> 1f
        BehaviorCandidateStatus.ACCEPTED -> 0.7f
        BehaviorCandidateStatus.REJECTED -> 0.35f
        BehaviorCandidateStatus.IGNORED -> 0.5f
        BehaviorCandidateStatus.DISABLED, BehaviorCandidateStatus.STALE -> 0f
    }

    private fun kindFactor(c: BehaviorCandidate): Float = when {
        c.kind == BehaviorKind.ROUTINE -> 1f
        else -> 0.75f
    }
}
