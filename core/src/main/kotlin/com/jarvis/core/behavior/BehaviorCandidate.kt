package com.jarvis.core.behavior

import java.util.UUID

/**
 * Кандидат поведения — гипотеза, не факт и не ToolCall.
 * Stage 6: SUGGEST_ONLY до явного feedback пользователя.
 */
data class BehaviorCandidate(
    val id: String = UUID.randomUUID().toString(),
    val kind: BehaviorKind,
    val family: PatternFamily,
    val description: String,
    val confidence: Float,
    val evidenceCount: Int,
    val firstObservedMillis: Long,
    val lastObservedMillis: Long,
    val supportingEvidenceIds: List<String> = emptyList(),
    val status: BehaviorCandidateStatus = BehaviorCandidateStatus.SUGGEST_ONLY,
    /** Стабильный ключ для DISABLE / dedup (например seq:HEADPHONES→pkg). */
    val patternKey: String,
    val entities: Map<String, String> = emptyMap(),
    val uniqueDays: Int = 1,
    val negativeEvidenceCount: Int = 0,
    val why: String = "",
) {
    fun withConfidence(c: Float, status: BehaviorCandidateStatus = this.status): BehaviorCandidate =
        copy(confidence = c.coerceIn(0f, 1f), status = status)
}
