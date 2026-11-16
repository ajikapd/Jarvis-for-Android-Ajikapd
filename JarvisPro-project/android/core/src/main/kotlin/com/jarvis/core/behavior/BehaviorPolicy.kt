package com.jarvis.core.behavior

/**
 * Policy suggest-only: DISABLED навсегда; REJECTED не предлагать снова без сильного recovery;
 * IGNORE обрабатывается в Store cooldown.
 */
class BehaviorPolicy(
    private val minSuggestConfidence: Float = 0.45f,
    private val minRoutineConfidence: Float = 0.55f,
    private val rejectRecoveryConfidence: Float = 0.70f,
) {

    fun filter(
        candidates: List<BehaviorCandidate>,
        disabledKeys: Set<String>,
        rejectedKeys: Set<String>,
    ): List<BehaviorCandidate> {
        return candidates.mapNotNull { raw ->
            if (raw.patternKey in disabledKeys) return@mapNotNull null
            var c = raw
            if (c.patternKey in rejectedKeys) {
                // REJECT: не возвращаем в suggest, пока confidence не восстановится сильно
                // с новым evidence (уже учтён negative в детекторе/store)
                if (c.confidence < rejectRecoveryConfidence) return@mapNotNull null
                c = c.copy(
                    negativeEvidenceCount = c.negativeEvidenceCount + 1,
                    confidence = (c.confidence - 0.12f).coerceAtLeast(0f),
                )
            }
            if (c.status == BehaviorCandidateStatus.STALE) return@mapNotNull null
            val need = if (c.kind == BehaviorKind.ROUTINE) minRoutineConfidence else minSuggestConfidence
            if (c.confidence < need) return@mapNotNull null
            c.copy(status = BehaviorCandidateStatus.SUGGEST_ONLY)
        }.sortedByDescending { it.confidence }
    }
}
