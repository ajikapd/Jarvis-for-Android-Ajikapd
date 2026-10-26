package com.jarvis.core.memory

import com.jarvis.core.companion.Sensitivity
import java.util.UUID

/**
 * Единственная точка Candidate → durable [MemoryEntry] → [MemoryRepository].
 * Detector сюда не ходит напрямую; только после [MemoryPolicy].
 */
class MemoryWriter(
    private val repository: MemoryRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * @return сохранённая запись или null если REJECT / SECRET / skip.
     */
    fun persist(decision: MemoryPolicyDecision): MemoryEntry? {
        if (decision.disposition == MemoryDisposition.REJECT) return null
        val c = decision.candidate
        if (c.sensitivity == Sensitivity.SECRET) return null

        val now = clock()
        val expires = MemoryRetention.expiresAt(
            nowMillis = now,
            kind = c.kind,
            provenance = c.provenance,
            sensitivity = c.sensitivity,
            confidence = c.confidence,
            disposition = decision.disposition,
        )

        // Upsert by same type + content + package (refresh evidence)
        val existing = repository.query(
            MemoryQuery(
                type = c.kind,
                textContains = c.statement.take(40),
                packageName = c.packageName,
                includeExpired = true,
                limit = 20,
            ),
        ).firstOrNull {
            it.content.equals(c.statement, ignoreCase = true) &&
                it.packageName == c.packageName &&
                it.status != MemoryStatus.DELETED
        }

        val entry = if (existing != null) {
            existing.copy(
                confidence = maxOf(existing.confidence, c.confidence),
                updatedAtMillis = now,
                lastObservedAtMillis = maxOf(existing.lastObservedAtMillis, c.lastSeenMillis),
                evidenceCount = maxOf(existing.evidenceCount, c.evidenceCount),
                evidenceObservationIds = (existing.evidenceObservationIds + c.evidenceObservationIds)
                    .distinct()
                    .takeLast(24),
                expiresAtMillis = expires,
                status = MemoryStatus.UPDATED,
                disposition = decision.disposition,
                entities = existing.entities + c.entities,
            ).also { repository.update(it) }
        } else {
            MemoryEntry(
                id = c.id.ifBlank { UUID.randomUUID().toString() },
                type = c.kind,
                content = c.statement,
                provenance = c.provenance,
                confidence = c.confidence,
                sensitivity = c.sensitivity,
                createdAtMillis = now,
                updatedAtMillis = now,
                firstObservedAtMillis = c.firstSeenMillis,
                lastObservedAtMillis = c.lastSeenMillis,
                expiresAtMillis = expires,
                evidenceCount = c.evidenceCount,
                evidenceObservationIds = c.evidenceObservationIds.takeLast(24),
                packageName = c.packageName,
                entities = c.entities,
                status = MemoryStatus.ACTIVE,
                disposition = decision.disposition,
            ).also { repository.save(it) }
        }
        return entry
    }
}
