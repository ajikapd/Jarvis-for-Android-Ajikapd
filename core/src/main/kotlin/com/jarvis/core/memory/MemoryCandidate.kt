package com.jarvis.core.memory

import com.jarvis.core.companion.Sensitivity
import java.util.UUID

/**
 * Кандидат в память — ещё не факт «запомнил».
 * Stage 5: detector → filter → policy → disposition.
 */
data class MemoryCandidate(
    val id: String = UUID.randomUUID().toString(),
    val kind: MemoryKind,
    val provenance: MemoryProvenance,
    val statement: String,
    val evidenceCount: Int,
    val confidence: Float,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val sensitivity: Sensitivity,
    val packageName: String? = null,
    val evidenceObservationIds: List<String> = emptyList(),
    val entities: Map<String, String> = emptyMap(),
)

data class MemoryPolicyDecision(
    val disposition: MemoryDisposition,
    val reason: String,
    val candidate: MemoryCandidate,
)
