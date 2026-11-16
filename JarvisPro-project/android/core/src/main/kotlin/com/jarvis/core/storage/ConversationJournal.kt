package com.jarvis.core.storage

import android.content.Context
import android.util.Log
import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.privacy.SensitivityClassifier
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Процесс-уровневая точка записи истории диалога.
 *
 * - session_id — новый UUID на запуск процесса;
 * - на диск уходит только `mask(text)`, причём для user и для assistant;
 * - запись асинхронная (один фоновый поток), поэтому главный поток не ждёт SQLite;
 * - одинаковая реплика, пришедшая из brain и из UI в пределах
 *   [ConversationDedupe.WINDOW_MS], пишется один раз и получает один id.
 */
object ConversationJournal {

    data class Entry(
        val id: String,
        val role: String,
        val text: String,
        val createdAt: Long,
        val keep: Boolean,
    )

    data class Stats(
        val count: Int,
        val keepCount: Int,
        val oldestAt: Long,
        val newestAt: Long,
    )

    private const val TAG = "Jarvis.ChatJournal"

    @Volatile
    private var repo: ConversationRepository? = null

    @Volatile
    private var executor: Executor? = null

    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        private set

    private val lastLock = Any()
    private var lastRole: String? = null
    private var lastText: String? = null
    private var lastAt: Long = 0L
    private var lastId: String? = null

    /** Вызывается один раз при старте процесса. Повторный вызов безопасен. */
    fun install(context: Context) {
        synchronized(this) {
            if (repo != null) return
            repo = ConversationRepository(JarvisDb.get(context.applicationContext))
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "jarvis-chat-journal").apply { isDaemon = true }
            }
            sessionId = UUID.randomUUID().toString()
            Log.i(TAG, "installed session=${sessionId.take(8)}")
        }
    }

    fun isInstalled(): Boolean = repo != null

    fun appendUser(text: String): String? = append(JarvisSchema.ROLE_USER, text)

    fun appendAssistant(text: String): String? = append(JarvisSchema.ROLE_ASSISTANT, text)

    private fun append(role: String, text: String): String? {
        val r = repo ?: return null
        val masked = SensitivityClassifier.mask(text).trim()
        if (masked.isEmpty()) return null
        // Двойная страховка: SECRET не должен попасть на диск даже в masked-виде.
        if (SensitivityClassifier.classify(masked) == Sensitivity.SECRET) return null

        val now = System.currentTimeMillis()
        synchronized(lastLock) {
            if (ConversationDedupe.isDuplicate(lastRole, lastText, lastAt, role, masked, now)) {
                return lastId
            }
            val id = UUID.randomUUID().toString()
            lastRole = role
            lastText = masked
            lastAt = now
            lastId = id
            val exec = executor
            if (exec == null) return null
            exec.execute { runCatching { r.append(id, role, masked, sessionId, now) } }
            return id
        }
    }

    /** Последние [limit] реплик не старше [maxAgeMs] в хронологическом порядке. */
    fun recent(limit: Int, maxAgeMs: Long): List<Entry> {
        val r = repo ?: return emptyList()
        val startedAt = System.currentTimeMillis()
        val rows = runCatching { r.loadRecent(limit, maxAgeMs) }.getOrDefault(emptyList())
        Log.i(TAG, "recent limit=$limit age=${maxAgeMs}ms size=${rows.size} in ${System.currentTimeMillis() - startedAt}ms")
        return rows.map { Entry(it.id, it.role, it.text, it.createdAt, it.keep) }
    }

    fun setKeep(id: String?, keep: Boolean) {
        val r = repo ?: return
        val target = id ?: return
        executor?.execute { runCatching { r.setKeep(target, keep) } }
    }

    fun expire(now: Long = System.currentTimeMillis()): Int {
        val r = repo ?: return 0
        val startedAt = System.currentTimeMillis()
        val removed = runCatching { r.expire(now) }.getOrDefault(0)
        Log.i(TAG, "expire removed=$removed in ${System.currentTimeMillis() - startedAt}ms")
        return removed
    }

    fun clearAll() {
        val r = repo ?: return
        synchronized(lastLock) {
            lastRole = null
            lastText = null
            lastAt = 0L
            lastId = null
        }
        executor?.execute { runCatching { r.clear() } }
    }

    fun stats(): Stats? {
        val r = repo ?: return null
        return runCatching {
            val boundaries = r.boundaries()
            Stats(
                count = r.count(),
                keepCount = r.keepCount(),
                oldestAt = boundaries.first,
                newestAt = boundaries.second,
            )
        }.getOrNull()
    }

    /** Только для debug-проверок на устройстве: есть ли подстрока (текст не выводится). */
    fun containsText(needle: String): Boolean =
        runCatching { repo?.containsText(needle) == true }.getOrDefault(false)
}
