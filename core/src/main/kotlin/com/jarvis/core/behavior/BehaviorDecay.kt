package com.jarvis.core.behavior

import kotlin.math.exp

/**
 * Если закономерность долго не подтверждается — confidence падает → STALE.
 */
object BehaviorDecay {

    private const val DAY_MS = 86_400_000.0

    fun apply(
        candidate: BehaviorCandidate,
        nowMillis: Long,
        halfLifeDays: Double = 14.0,
        staleBelow: Float = 0.35f,
    ): BehaviorCandidate {
        if (candidate.status == BehaviorCandidateStatus.DISABLED ||
            candidate.status == BehaviorCandidateStatus.ACCEPTED
        ) {
            return candidate
        }
        val days = ((nowMillis - candidate.lastObservedMillis).coerceAtLeast(0) / DAY_MS)
        if (days < 1.0) return candidate
        val factor = exp(-days / halfLifeDays).toFloat()
        val next = (candidate.confidence * (0.55f + 0.45f * factor)).coerceIn(0f, 1f)
        val status = if (next < staleBelow) BehaviorCandidateStatus.STALE else candidate.status
        return candidate.withConfidence(next, status)
    }
}
