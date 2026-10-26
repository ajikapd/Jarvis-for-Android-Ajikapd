package com.jarvis.brain

/**
 * Контракт долгой памяти (JARVIS_BRAIN_ARCHITECTURE §4.2).
 * Реализация — SQLite (см. [LongTermMemory]); позже векторные факты
 * подключаются через ту же абстракцию, контракт не меняется.
 */
interface LongTermMemoryInterface {

    /** Возвращает top-N фактов, релевантных запросу. */
    fun retrieve(query: String, limit: Int = 3): List<LongTermMemory.MemoryFact>

    fun add(text: String, category: String, source: String = "user"): Boolean

    fun all(): List<LongTermMemory.MemoryFact>

    fun remove(id: Long): Boolean

    fun clear()

    fun count(): Int
}