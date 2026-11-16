package com.jarvis.core.memory

/**
 * Структурированный recall — отдельно от [MemoryRepository].
 * Без embeddings / LLM: type / status / text / recent.
 */
class MemoryRecall(
    private val repository: MemoryRepository,
) {
    fun byType(type: MemoryKind, limit: Int = 30): List<MemoryEntry> =
        repository.query(MemoryQuery(type = type, limit = limit))

    fun byStatus(status: MemoryStatus, limit: Int = 30): List<MemoryEntry> =
        repository.query(MemoryQuery(status = status, includeExpired = true, limit = limit))

    fun active(limit: Int = 40): List<MemoryEntry> =
        repository.query(MemoryQuery(status = MemoryStatus.ACTIVE, limit = limit)) +
            repository.query(MemoryQuery(status = MemoryStatus.UPDATED, limit = limit))

    fun temporary(limit: Int = 30): List<MemoryEntry> =
        repository.query(MemoryQuery(limit = 200))
            .filter { it.disposition == MemoryDisposition.TEMPORARY && it.isAlive() }
            .take(limit)

    fun staleOrExpired(limit: Int = 30): List<MemoryEntry> =
        repository.query(MemoryQuery(status = MemoryStatus.STALE, includeExpired = true, limit = limit)) +
            repository.query(MemoryQuery(status = MemoryStatus.EXPIRED, includeExpired = true, limit = limit))

    fun textSearch(query: String, limit: Int = 20): List<MemoryEntry> =
        repository.query(MemoryQuery(textContains = query, limit = limit))

    fun recent(limit: Int = 20): List<MemoryEntry> =
        repository.query(MemoryQuery(limit = limit))

    fun preferences(): List<MemoryEntry> = byType(MemoryKind.PREFERENCE)

    fun explain(id: String): String? {
        val e = repository.get(id) ?: return null
        return buildString {
            appendLine(e.content)
            appendLine("provenance=${e.provenance} type=${e.type} status=${e.status}")
            appendLine("confidence=${e.confidence} sensitivity=${e.sensitivity}")
            appendLine("evidence=${e.evidenceCount} ids=${e.evidenceObservationIds.take(8)}")
            appendLine("first=${e.firstObservedAtMillis} last=${e.lastObservedAtMillis}")
            appendLine("expires=${e.expiresAtMillis ?: "never"}")
            if (e.entities.isNotEmpty()) appendLine("entities=${e.entities}")
        }.trim()
    }
}
