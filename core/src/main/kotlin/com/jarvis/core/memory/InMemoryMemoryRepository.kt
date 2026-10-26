package com.jarvis.core.memory

import java.util.concurrent.ConcurrentHashMap

/** In-memory реализация для unit-тестов (без Android Context). */
class InMemoryMemoryRepository : MemoryRepository {

    private val map = ConcurrentHashMap<String, MemoryEntry>()

    override fun save(entry: MemoryEntry): MemoryEntry {
        map[entry.id] = entry
        return entry
    }

    override fun get(id: String): MemoryEntry? = map[id]

    override fun update(entry: MemoryEntry): MemoryEntry? {
        if (!map.containsKey(entry.id)) return null
        map[entry.id] = entry
        return entry
    }

    override fun delete(id: String): Boolean {
        val cur = map[id] ?: return false
        map[id] = cur.copy(
            status = MemoryStatus.DELETED,
            updatedAtMillis = System.currentTimeMillis(),
        )
        return true
    }

    override fun query(query: MemoryQuery): List<MemoryEntry> {
        val needle = query.textContains?.lowercase()?.trim().orEmpty()
        return map.values
            .asSequence()
            .filter { e ->
                if (!query.includeDeleted && e.status == MemoryStatus.DELETED) return@filter false
                if (!query.includeExpired && e.status == MemoryStatus.EXPIRED) return@filter false
                if (query.type != null && e.type != query.type) return@filter false
                if (query.status != null && e.status != query.status) return@filter false
                if (query.provenance != null && e.provenance != query.provenance) return@filter false
                if (query.packageName != null && e.packageName != query.packageName) return@filter false
                if (needle.isNotEmpty() && !e.content.lowercase().contains(needle)) return@filter false
                true
            }
            .sortedByDescending { it.updatedAtMillis }
            .take(query.limit.coerceAtLeast(0))
            .toList()
    }

    override fun expire(nowMillis: Long): Int {
        var n = 0
        for ((id, e) in map) {
            if (e.status == MemoryStatus.DELETED || e.status == MemoryStatus.EXPIRED) continue
            val exp = e.expiresAtMillis
            if (exp != null && nowMillis >= exp) {
                map[id] = e.copy(status = MemoryStatus.EXPIRED, updatedAtMillis = nowMillis)
                n++
                continue
            }
            val staleAfter = MemoryRetention.staleAfterMillis(e.type, e.provenance)
            if (e.status == MemoryStatus.ACTIVE &&
                nowMillis - e.lastObservedAtMillis >= staleAfter
            ) {
                map[id] = e.copy(status = MemoryStatus.STALE, updatedAtMillis = nowMillis)
                n++
            }
        }
        return n
    }

    override fun clearAll() {
        map.clear()
    }
}
