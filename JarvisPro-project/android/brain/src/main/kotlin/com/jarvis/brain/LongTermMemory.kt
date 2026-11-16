package com.jarvis.brain

import android.content.Context
import com.jarvis.core.storage.FactAddResult
import com.jarvis.core.storage.FactsRepository
import com.jarvis.core.storage.JarvisDb
import com.jarvis.core.storage.LegacyFactsMigration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Долгая память (JARVIS_BRAIN_ARCHITECTURE §4.2).
 *
 * Тонкая обёртка над [FactsRepository] (`jarvis_core.db`): прежний публичный API
 * сохранён, чтобы BrainGraph, оркестратор и UI не менялись. Собственного
 * SQLite-хелпера больше нет — старая `jarvis_memory.db` только читается
 * одноразовой миграцией и остаётся на диске как бэкап.
 *
 * При первом обращении выполняется миграция `jarvis_memory.db → facts`
 * (source='seed' пропускается, SECRET не пишется, дедуп по тексту).
 */
class LongTermMemory(context: Context) : LongTermMemoryInterface {

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val migrationDone = AtomicBoolean(false)

    private val repo: FactsRepository by lazy { FactsRepository(JarvisDb.get(appContext)) }

    private fun ensureReady() {
        if (migrationDone.compareAndSet(false, true)) {
            runCatching { LegacyFactsMigration(repo).migrateIfNeeded(appContext) }
        }
    }

    /** Возвращает top-N фактов, релевантных запросу. */
    override fun retrieve(query: String, limit: Int): List<MemoryFact> = lock.withLock {
        ensureReady()
        repo.retrieve(query, limit).map { it.toMemoryFact() }
    }

    /**
     * @return true только если факт реально записан; SECRET/дубль/невалидный — false.
     */
    override fun add(text: String, category: String, source: String): Boolean = lock.withLock {
        ensureReady()
        repo.add(text, category, source) == FactAddResult.STORED
    }

    override fun all(): List<MemoryFact> = lock.withLock {
        ensureReady()
        repo.list().map { it.toMemoryFact() }
    }

    /** id здесь — rowid строки (исторический Long-контракт UI/тестов). */
    override fun remove(id: Long): Boolean = lock.withLock {
        ensureReady()
        repo.deleteRow(id)
    }

    override fun clear() = lock.withLock {
        ensureReady()
        repo.clearAll()
    }

    override fun count(): Int = lock.withLock {
        ensureReady()
        repo.count()
    }

    private fun FactsRepository.StoredFact.toMemoryFact(): MemoryFact = MemoryFact(
        id = rowId,
        text = text,
        category = category,
        createdAt = createdAt,
        source = source,
    )

    data class MemoryFact(
        val id: Long,
        val text: String,
        val category: String,
        val createdAt: Long,
        val source: String
    )
}
