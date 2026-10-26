package com.jarvis.core.memory

/**
 * Абстракция durable storage. Detector/Policy не зависят от SQLite.
 */
interface MemoryRepository {
    fun save(entry: MemoryEntry): MemoryEntry
    fun get(id: String): MemoryEntry?
    fun update(entry: MemoryEntry): MemoryEntry?
    fun delete(id: String): Boolean
    fun query(query: MemoryQuery): List<MemoryEntry>
    /** Пометить/перевести просроченные и устаревшие; вернуть число изменений. */
    fun expire(nowMillis: Long = System.currentTimeMillis()): Int
    fun clearAll()
}

data class MemoryQuery(
    val type: MemoryKind? = null,
    val status: MemoryStatus? = null,
    val provenance: MemoryProvenance? = null,
    val textContains: String? = null,
    val packageName: String? = null,
    val includeExpired: Boolean = false,
    val includeDeleted: Boolean = false,
    val limit: Int = 50,
)
