package com.jarvis.core.behavior

import com.jarvis.core.companion.ObservationSource
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Confidence для BehaviorCandidate — Stage 6.5 hardened.
 * Не «N events = score»; есть cap, single-event floor, negative evidence.
 */
object ConfidenceModel {

    const val HARD_CAP = 0.92f
    const val SINGLE_EVENT_MAX = 0.22f

    fun score(
        evidenceCount: Int,
        uniqueDays: Int,
        lastObservedMillis: Long,
        nowMillis: Long,
        sourceReliability: Float = 0.85f,
        negativeEvidenceCount: Int = 0,
        regularityScore: Float = 0.5f,
    ): Float {
        if (evidenceCount <= 0) return 0f
        if (evidenceCount == 1) {
            return min(SINGLE_EVENT_MAX, 0.12f + 0.05f * sourceReliability)
        }

        val freq = (ln(1.0 + evidenceCount) / ln(1.0 + 20.0)).toFloat().coerceIn(0f, 1f)
        val daysSince = ((nowMillis - lastObservedMillis).coerceAtLeast(0).toDouble() / 86_400_000.0)
        val recency = exp(-daysSince / 10.0).toFloat().coerceIn(0f, 1f)
        val daySpread = min(1f, uniqueDays / max(1f, min(evidenceCount, 7).toFloat()))
        val negativePenalty = (negativeEvidenceCount * 0.10f).coerceAtMost(0.55f)

        val raw = 0.34f * freq +
            0.22f * recency +
            0.18f * daySpread +
            0.16f * regularityScore.coerceIn(0f, 1f) +
            0.10f * sourceReliability.coerceIn(0f, 1f)

        return (raw - negativePenalty).coerceIn(0f, HARD_CAP)
    }

    /** Слияние: новое evidence не должно прыгать выше cap; учитываем прошлый score. */
    fun merge(previous: Float?, fresh: Float, newEvidenceAdded: Boolean): Float {
        val f = fresh.coerceIn(0f, HARD_CAP)
        if (previous == null) return f
        val p = previous.coerceIn(0f, HARD_CAP)
        return if (!newEvidenceAdded) {
            // Без нового evidence не расти (только decay снаружи)
            min(p, f).coerceIn(0f, HARD_CAP)
        } else {
            // Плавный рост к fresh, не выше cap
            val grown = max(p, p * 0.65f + f * 0.35f)
            max(grown, f * 0.85f).coerceIn(0f, HARD_CAP)
        }
    }

    fun sourceWeight(source: ObservationSource): Float = when (source) {
        ObservationSource.ACCESSIBILITY -> 0.9f
        ObservationSource.SYSTEM_BROADCAST -> 0.85f
        ObservationSource.USAGE_STATS -> 0.65f
        ObservationSource.NOTIFICATION_LISTENER -> 0.55f
        ObservationSource.VOICE, ObservationSource.TOOL, ObservationSource.INTERNAL -> 0.7f
    }
}
