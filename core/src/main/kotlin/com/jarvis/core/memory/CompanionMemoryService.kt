package com.jarvis.core.memory

import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity

/**
 * Stage 5.5 facade: detect → filter → policy → writer → repository.
 * Не смешивает bus/diagnostics с storage.
 */
class CompanionMemoryService(
    private val repository: MemoryRepository,
    private val detector: MemoryCandidateDetector = MemoryCandidateDetector(),
    private val sensitivityFilter: SensitivityFilter = SensitivityFilter(),
    private val policy: MemoryPolicy = MemoryPolicy(),
    private val writer: MemoryWriter = MemoryWriter(repository),
    val recall: MemoryRecall = MemoryRecall(repository),
) {
    fun ingest(
        snapshot: ContextSnapshot,
        recent: List<PhoneObservation>,
    ): List<MemoryEntry> {
        repository.expire()
        val out = mutableListOf<MemoryEntry>()
        for (c in detector.detect(snapshot, recent)) {
            val decision = sensitivityFilter.filter(c) ?: policy.decide(c)
            writer.persist(decision)?.let { out += it }
        }
        return out
    }

    /** Явное USER_STATED предпочтение (голос/UI) — через ту же Policy. */
    fun rememberUserStatement(
        statement: String,
        kind: MemoryKind = MemoryKind.PREFERENCE,
        confidence: Float = 1f,
        sensitivity: Sensitivity = Sensitivity.PRIVATE,
    ): MemoryEntry? {
        val now = System.currentTimeMillis()
        val c = MemoryCandidate(
            kind = kind,
            provenance = MemoryProvenance.USER_STATED,
            statement = statement.trim(),
            evidenceCount = 1,
            confidence = confidence,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = sensitivity,
            entities = mapOf("type" to "USER_STATEMENT"),
        )
        val decision = sensitivityFilter.filter(c) ?: policy.decide(c)
        return writer.persist(decision)
    }

    fun expireNow(): Int = repository.expire()

    fun repository(): MemoryRepository = repository
}
