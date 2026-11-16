package com.jarvis.core.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Дедупликация парных записей: одна и та же реплика приходит из brain
 * (ConversationContext) и из UI (ConversationStore). Чистая функция.
 */
object ConversationDedupe {

    /** Окно, в котором одинаковые role+text считаются одной репликой. */
    const val WINDOW_MS: Long = 2_000L

    fun isDuplicate(
        previousRole: String?,
        previousText: String?,
        previousAt: Long,
        role: String,
        text: String,
        nowMillis: Long,
        windowMs: Long = WINDOW_MS,
    ): Boolean {
        if (previousRole != role || previousText != text) return false
        if (previousAt <= 0L) return false
        return (nowMillis - previousAt) in 0..windowMs
    }
}

/**
 * Слой доступа к `conversation_messages` (`jarvis_core.db`).
 *
 * Текст всегда приходит уже замаскированным ([ConversationJournal] делает mask),
 * поэтому репозиторий не знает про классификатор.
 */
class ConversationRepository(private val helper: JarvisDb) {

    data class Message(
        val id: String,
        val sessionId: String,
        val role: String,
        val text: String,
        val createdAt: Long,
        val keep: Boolean,
    )

    fun append(
        id: String,
        role: String,
        maskedText: String,
        sessionId: String,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("session_id", sessionId)
            put("role", role)
            put("text", maskedText)
            put("created_at", createdAt)
            put("keep", 0)
        }
        helper.writableDatabase.insertWithOnConflict(
            JarvisSchema.TABLE_MESSAGES,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Последние [limit] сообщений не старше [maxAgeMs], в хронологическом порядке. */
    fun loadRecent(
        limit: Int,
        maxAgeMs: Long,
        now: Long = System.currentTimeMillis(),
    ): List<Message> {
        if (limit <= 0) return emptyList()
        val from = now - maxAgeMs
        val out = ArrayList<Message>(limit)
        helper.readableDatabase.rawQuery(
            "SELECT id, session_id, role, text, created_at, keep FROM conversation_messages " +
                "WHERE created_at >= ? ORDER BY created_at DESC, rowid DESC LIMIT ?",
            arrayOf(from.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Message(
                    id = c.getString(0),
                    sessionId = c.getString(1),
                    role = c.getString(2),
                    text = c.getString(3),
                    createdAt = c.getLong(4),
                    keep = c.getInt(5) != 0,
                )
            }
        }
        out.reverse()
        return out
    }

    fun setKeep(id: String, keep: Boolean): Boolean {
        val values = ContentValues().apply { put("keep", if (keep) 1 else 0) }
        return helper.writableDatabase.update(
            JarvisSchema.TABLE_MESSAGES,
            values,
            "id = ?",
            arrayOf(id),
        ) > 0
    }

    /** Удаляет записи старше TTL, кроме keep=1. @return сколько удалено. */
    fun expire(now: Long = System.currentTimeMillis()): Int =
        helper.writableDatabase.delete(
            JarvisSchema.TABLE_MESSAGES,
            "keep = 0 AND created_at < ?",
            arrayOf(RetentionPolicy.cutoff(now).toString()),
        )

    fun clear() {
        helper.writableDatabase.delete(JarvisSchema.TABLE_MESSAGES, null, null)
    }

    fun count(): Int = countWhere("1 = 1")

    fun keepCount(): Int = countWhere("keep = 1")

    fun countSince(sinceMillis: Long): Int = countWhere("created_at >= $sinceMillis")

    /** Метка времени самой старой/новой записи, 0 — если записей нет. */
    fun boundaries(): Pair<Long, Long> {
        helper.readableDatabase.rawQuery(
            "SELECT MIN(created_at), MAX(created_at) FROM conversation_messages",
            null,
        ).use { c ->
            if (!c.moveToFirst()) return 0L to 0L
            val oldest = if (c.isNull(0)) 0L else c.getLong(0)
            val newest = if (c.isNull(1)) 0L else c.getLong(1)
            return oldest to newest
        }
    }

    /**
     * Диагностика без текстов: есть ли в истории подстрока [needle].
     * Нужна только DEBUG-хуку (синтетические проверки на устройстве).
     */
    fun containsText(needle: String): Boolean {
        if (needle.isEmpty()) return false
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM conversation_messages WHERE text LIKE ? LIMIT 1",
            arrayOf("%$needle%"),
        ).use { c -> return c.moveToFirst() }
    }

    private fun countWhere(where: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM conversation_messages WHERE $where",
            null,
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}
