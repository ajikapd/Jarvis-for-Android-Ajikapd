package com.jarvis.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarvis.core.companion.Sensitivity
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable SQLite store для Companion Memory (отдельно от brain LongTermMemory).
 * Без Room / embeddings / внешних deps.
 */
class SqliteMemoryRepository(context: Context) : MemoryRepository {

    private val lock = ReentrantLock()
    private val db by lazy { Helper(context.applicationContext).writableDatabase }

    override fun save(entry: MemoryEntry): MemoryEntry = lock.withLock {
        db.insertWithOnConflict(
            TABLE,
            null,
            toValues(entry),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        entry
    }

    override fun get(id: String): MemoryEntry? = lock.withLock {
        db.rawQuery("SELECT * FROM $TABLE WHERE id = ? LIMIT 1", arrayOf(id)).use { c ->
            if (c.moveToFirst()) read(c) else null
        }
    }

    override fun update(entry: MemoryEntry): MemoryEntry? = lock.withLock {
        val n = db.update(TABLE, toValues(entry), "id = ?", arrayOf(entry.id))
        if (n > 0) entry else null
    }

    override fun delete(id: String): Boolean = lock.withLock {
        val cur = getUnlocked(id) ?: return@withLock false
        val updated = cur.copy(
            status = MemoryStatus.DELETED,
            updatedAtMillis = System.currentTimeMillis(),
        )
        db.update(TABLE, toValues(updated), "id = ?", arrayOf(id)) > 0
    }

    override fun query(query: MemoryQuery): List<MemoryEntry> = lock.withLock {
        val all = mutableListOf<MemoryEntry>()
        db.rawQuery("SELECT * FROM $TABLE ORDER BY updated_at DESC", null).use { c ->
            while (c.moveToNext()) all += read(c)
        }
        val needle = query.textContains?.lowercase()?.trim().orEmpty()
        all.asSequence()
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
            .take(query.limit.coerceAtLeast(0))
            .toList()
    }

    override fun expire(nowMillis: Long): Int = lock.withLock {
        var n = 0
        val all = mutableListOf<MemoryEntry>()
        db.rawQuery("SELECT * FROM $TABLE", null).use { c ->
            while (c.moveToNext()) all += read(c)
        }
        for (e in all) {
            if (e.status == MemoryStatus.DELETED || e.status == MemoryStatus.EXPIRED) continue
            val exp = e.expiresAtMillis
            if (exp != null && nowMillis >= exp) {
                db.update(
                    TABLE,
                    toValues(e.copy(status = MemoryStatus.EXPIRED, updatedAtMillis = nowMillis)),
                    "id = ?",
                    arrayOf(e.id),
                )
                n++
                continue
            }
            val staleAfter = MemoryRetention.staleAfterMillis(e.type, e.provenance)
            if (e.status == MemoryStatus.ACTIVE &&
                nowMillis - e.lastObservedAtMillis >= staleAfter
            ) {
                db.update(
                    TABLE,
                    toValues(e.copy(status = MemoryStatus.STALE, updatedAtMillis = nowMillis)),
                    "id = ?",
                    arrayOf(e.id),
                )
                n++
            }
        }
        n
    }

    override fun clearAll() = lock.withLock {
        db.delete(TABLE, null, null)
        Unit
    }

    private fun getUnlocked(id: String): MemoryEntry? {
        db.rawQuery("SELECT * FROM $TABLE WHERE id = ? LIMIT 1", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    private fun toValues(e: MemoryEntry): ContentValues = ContentValues().apply {
        put("id", e.id)
        put("type", e.type.name)
        put("content", e.content)
        put("provenance", e.provenance.name)
        put("confidence", e.confidence)
        put("sensitivity", e.sensitivity.name)
        put("created_at", e.createdAtMillis)
        put("updated_at", e.updatedAtMillis)
        put("first_observed_at", e.firstObservedAtMillis)
        put("last_observed_at", e.lastObservedAtMillis)
        if (e.expiresAtMillis == null) putNull("expires_at") else put("expires_at", e.expiresAtMillis)
        put("evidence_count", e.evidenceCount)
        put("evidence_ids", e.evidenceObservationIds.joinToString(","))
        put("package_name", e.packageName)
        put("entities", encodeEntities(e.entities))
        put("status", e.status.name)
        put("disposition", e.disposition.name)
    }

    private fun read(c: android.database.Cursor): MemoryEntry {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        val expIdx = col("expires_at")
        val expires = if (c.isNull(expIdx)) null else c.getLong(expIdx)
        val evidenceRaw = c.getString(col("evidence_ids")).orEmpty()
        return MemoryEntry(
            id = c.getString(col("id")),
            type = MemoryKind.valueOf(c.getString(col("type"))),
            content = c.getString(col("content")),
            provenance = MemoryProvenance.valueOf(c.getString(col("provenance"))),
            confidence = c.getFloat(col("confidence")),
            sensitivity = Sensitivity.valueOf(c.getString(col("sensitivity"))),
            createdAtMillis = c.getLong(col("created_at")),
            updatedAtMillis = c.getLong(col("updated_at")),
            firstObservedAtMillis = c.getLong(col("first_observed_at")),
            lastObservedAtMillis = c.getLong(col("last_observed_at")),
            expiresAtMillis = expires,
            evidenceCount = c.getInt(col("evidence_count")),
            evidenceObservationIds = evidenceRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            packageName = c.getString(col("package_name")),
            entities = decodeEntities(c.getString(col("entities"))),
            status = MemoryStatus.valueOf(c.getString(col("status"))),
            disposition = MemoryDisposition.valueOf(c.getString(col("disposition"))),
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                  id TEXT PRIMARY KEY NOT NULL,
                  type TEXT NOT NULL,
                  content TEXT NOT NULL,
                  provenance TEXT NOT NULL,
                  confidence REAL NOT NULL,
                  sensitivity TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  first_observed_at INTEGER NOT NULL,
                  last_observed_at INTEGER NOT NULL,
                  expires_at INTEGER,
                  evidence_count INTEGER NOT NULL,
                  evidence_ids TEXT NOT NULL,
                  package_name TEXT,
                  entities TEXT NOT NULL,
                  status TEXT NOT NULL,
                  disposition TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_mem_type ON $TABLE(type)")
            db.execSQL("CREATE INDEX idx_mem_status ON $TABLE(status)")
            db.execSQL("CREATE INDEX idx_mem_updated ON $TABLE(updated_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val DB_NAME = "jarvis_companion_memory.db"
        private const val DB_VERSION = 1
        private const val TABLE = "companion_memory"

        fun encodeEntities(map: Map<String, String>): String =
            map.entries.joinToString(";") { (k, v) ->
                "${k.replace('=', '_').replace(';', '_')}=${v.replace(';', '_')}"
            }

        fun decodeEntities(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
        }
    }
}
