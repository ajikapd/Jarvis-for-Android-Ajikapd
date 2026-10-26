package com.jarvis.core.memory

/**
 * Stage 21: Memory 2.0 — расширенные типы. Не всё наблюдение → durable memory.
 */
enum class Memory2Kind {
    PREFERENCE,
    BEHAVIOR,
    ROUTINE,
    CONTEXT,
    FEEDBACK,
    EXECUTION_HISTORY,
    TOOL_PERFORMANCE,
}

enum class Memory2Durability {
    EPHEMERAL,
    SESSION,
    DURABLE,
}

data class Memory2Record(
    val id: String,
    val kind: Memory2Kind,
    val text: String,
    val durability: Memory2Durability,
    val createdAtMillis: Long,
    val score: Float = 0.5f,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank())
        require(text.isNotBlank() && text.length <= 2_000)
        require(score in 0f..1f)
        require(metadata.size <= 16)
    }
}

/**
 * Политика: что можно сохранять в durable.
 */
object Memory2Policy {
    fun allowDurable(kind: Memory2Kind): Boolean = when (kind) {
        Memory2Kind.PREFERENCE,
        Memory2Kind.ROUTINE,
        Memory2Kind.FEEDBACK,
        Memory2Kind.BEHAVIOR,
        -> true
        Memory2Kind.CONTEXT,
        Memory2Kind.EXECUTION_HISTORY,
        Memory2Kind.TOOL_PERFORMANCE,
        -> false
    }

    fun clampDurability(kind: Memory2Kind, requested: Memory2Durability): Memory2Durability {
        if (requested == Memory2Durability.DURABLE && !allowDurable(kind)) {
            return Memory2Durability.SESSION
        }
        return requested
    }
}

class Memory2Store {
    private val records = LinkedHashMap<String, Memory2Record>()

    fun put(record: Memory2Record): Memory2Record {
        val durability = Memory2Policy.clampDurability(record.kind, record.durability)
        val stored = if (durability == record.durability) record else record.copy(durability = durability)
        records[stored.id] = stored
        return stored
    }

    fun find(id: String): Memory2Record? = records[id]

    fun byKind(kind: Memory2Kind): List<Memory2Record> =
        records.values.filter { it.kind == kind }

    fun durable(): List<Memory2Record> =
        records.values.filter { it.durability == Memory2Durability.DURABLE }

    fun size(): Int = records.size

    fun clearEphemeral() {
        records.entries.removeAll { it.value.durability == Memory2Durability.EPHEMERAL }
    }

    /** V2-04: lexical relevance for context injection (not full Memory V3). */
    fun searchRelevant(query: String, limit: Int = 5): List<Memory2Record> {
        val words = com.jarvis.core.skills.SkillRetriever.defaultNormalize(query)
        if (words.isEmpty()) return emptyList()
        return records.values.mapNotNull { r ->
            val rw = com.jarvis.core.skills.SkillRetriever.defaultNormalize(r.text)
            if (rw.isEmpty()) return@mapNotNull null
            val hits = words.count { it in rw.toSet() }
            if (hits == 0) return@mapNotNull null
            r to hits.toFloat() / words.size
        }.sortedByDescending { it.second }
            .take(limit.coerceIn(1, 12))
            .map { it.first }
    }
}
