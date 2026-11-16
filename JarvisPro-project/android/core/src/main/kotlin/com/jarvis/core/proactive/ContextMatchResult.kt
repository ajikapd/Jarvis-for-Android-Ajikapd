package com.jarvis.core.proactive

/**
 * Объяснимый результат сравнения BehaviorCandidate ↔ ContextSnapshot.
 * Не Boolean — diagnostics должны видеть matched/mismatched signals.
 */
data class ContextMatchResult(
    val matched: Boolean,
    /** 0..1 — сила совпадения контекста (без behavior confidence). */
    val score: Float,
    val matchedSignals: List<String> = emptyList(),
    val mismatchSignals: List<String> = emptyList(),
    /** Консервативно: follow-app уже на переднем плане. */
    val alreadySatisfied: Boolean = false,
) {
    fun withAlreadySatisfied(): ContextMatchResult =
        copy(alreadySatisfied = true, matched = false)
}

/**
 * Relevance отделён от BehaviorCandidate.confidence.
 */
data class RelevanceResult(
    val relevance: Float,
    val match: ContextMatchResult,
    val reasonCode: ProactiveReasonCode = ProactiveReasonCode.EVALUATED,
    val notes: List<String> = emptyList(),
)

/**
 * Веса relevance — deterministic и настраиваемые; не «финальный API».
 * Легко тюнить тестами / DecisionPolicy позже.
 */
data class RelevanceWeights(
    val contextMatch: Float = 0.55f,
    val recency: Float = 0.20f,
    val statusBoost: Float = 0.15f,
    val kindBoost: Float = 0.10f,
) {
    init {
        require(contextMatch >= 0f && recency >= 0f && statusBoost >= 0f && kindBoost >= 0f)
    }

    fun normalized(): RelevanceWeights {
        val s = contextMatch + recency + statusBoost + kindBoost
        if (s <= 0f) return this
        return RelevanceWeights(
            contextMatch = contextMatch / s,
            recency = recency / s,
            statusBoost = statusBoost / s,
            kindBoost = kindBoost / s,
        )
    }
}

/**
 * Веса сигналов внутри ContextMatcher (отдельно от RelevanceWeights).
 */
data class ContextMatchWeights(
    val trigger: Float = 0.45f,
    val followApp: Float = 0.25f,
    val hour: Float = 0.20f,
    val packageEntity: Float = 0.10f,
    /** Допуск часа для entities["hour"] (ROUTINE/TEMPORAL). */
    val hourTolerance: Int = 2,
) {
    fun normalized(): ContextMatchWeights {
        val s = trigger + followApp + hour + packageEntity
        if (s <= 0f) return this
        return copy(
            trigger = trigger / s,
            followApp = followApp / s,
            hour = hour / s,
            packageEntity = packageEntity / s,
        )
    }
}
