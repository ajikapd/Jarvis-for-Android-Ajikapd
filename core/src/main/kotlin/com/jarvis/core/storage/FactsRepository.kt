package com.jarvis.core.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.privacy.SensitivityClassifier
import java.util.UUID

/** Результат попытки записать факт. */
enum class FactAddResult {
    STORED,
    DUPLICATE,
    REJECTED_SECRET,
    REJECTED_TOO_SHORT,
    REJECTED_TOO_LONG,
}

/**
 * Слой доступа к таблице `facts` (`jarvis_core.db`).
 *
 * Все записи идут через [FactPolicies]: дедуп по нормализованному тексту,
 * запрет SECRET, mask() перед сохранением.
 */
class FactsRepository(private val helper: JarvisDb) {

    private val lock = Any()

    data class StoredFact(
        val id: String,
        val rowId: Long,
        val text: String,
        val category: String,
        val source: String,
        val weight: Float,
        val sensitivity: String,
        val createdAt: Long,
        val updatedAt: Long,
        val expiresAt: Long?,
    )

    fun add(
        text: String,
        category: String = "general",
        source: String = "user",
        createdAt: Long = System.currentTimeMillis(),
    ): FactAddResult = synchronized(lock) {
        val trimmed = text.trim()
        if (trimmed.length < FactPolicies.MIN_LENGTH) return FactAddResult.REJECTED_TOO_SHORT
        if (trimmed.length > FactPolicies.MAX_LENGTH) return FactAddResult.REJECTED_TOO_LONG
        if (FactPolicies.isSecret(trimmed)) return FactAddResult.REJECTED_SECRET

        val key = FactPolicies.normalizeForDedup(trimmed)
        val db = helper.writableDatabase
        if (existsNormalized(db, key)) return FactAddResult.DUPLICATE

        val stored = FactPolicies.diskText(trimmed)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("text", stored)
            put("category", category)
            put("source", source)
            put("weight", 1.0)
            put("sensitivity", SensitivityClassifier.classify(stored, null).name)
            put("created_at", createdAt)
            put("updated_at", now)
            putNull("expires_at")
        }
        db.insertOrThrow(JarvisSchema.TABLE_FACTS, null, values)
        FactAddResult.STORED
    }

    /** Все неистёкшие факты, новые сверху. */
    fun list(now: Long = System.currentTimeMillis()): List<StoredFact> = synchronized(lock) {
        val out = ArrayList<StoredFact>()
        helper.readableDatabase.rawQuery(
            "SELECT rowid, id, text, category, source, weight, sensitivity, created_at, updated_at, expires_at " +
                "FROM facts WHERE expires_at IS NULL OR expires_at > ? ORDER BY created_at DESC",
            arrayOf(now.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += StoredFact(
                    id = c.getString(1),
                    rowId = c.getLong(0),
                    text = c.getString(2),
                    category = c.getString(3),
                    source = c.getString(4),
                    weight = c.getFloat(5),
                    sensitivity = c.getString(6),
                    createdAt = c.getLong(7),
                    updatedAt = c.getLong(8),
                    expiresAt = if (c.isNull(9)) null else c.getLong(9),
                )
            }
        }
        out
    }

    /** Top-N по лексическому скору (прежняя логика LongTermMemory.retrieve). */
    fun retrieve(query: String, limit: Int = 3): List<StoredFact> =
        FactScoring.topN(query, list(), limit) { it.text }

    fun findById(id: String): StoredFact? = synchronized(lock) {
        list().firstOrNull { it.id == id }
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        helper.writableDatabase.delete(JarvisSchema.TABLE_FACTS, "id = ?", arrayOf(id)) > 0
    }

    /** Удаление по rowid — для старого API [com.jarvis.brain.LongTermMemory.remove] (Long). */
    fun deleteRow(rowId: Long): Boolean = synchronized(lock) {
        helper.writableDatabase.delete(JarvisSchema.TABLE_FACTS, "rowid = ?", arrayOf(rowId.toString())) > 0
    }

    fun clearAll() {
        synchronized(lock) { helper.writableDatabase.delete(JarvisSchema.TABLE_FACTS, null, null) }
    }

    fun count(): Int = synchronized(lock) {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM facts", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun countBySensitivity(sensitivity: Sensitivity): Int = synchronized(lock) {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM facts WHERE sensitivity = ?",
            arrayOf(sensitivity.name),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun existsNormalized(db: SQLiteDatabase, normalized: String): Boolean {
        db.rawQuery("SELECT text FROM facts", null).use { c ->
            while (c.moveToNext()) {
                if (FactPolicies.normalizeForDedup(c.getString(0)) == normalized) return true
            }
        }
        return false
    }
}

/**
 * Одноразовый перенос фактов из старой `jarvis_memory.db` в `jarvis_core.db`.
 *
 * - старый файл открывается только на чтение и не изменяется;
 * - source='seed' (демо-факты) пропускается;
 * - SECRET-строки не переносятся, текст перед записью маскируется;
 * - дедуп по нормализованному тексту, поэтому повторный запуск безопасен;
 * - факт выполнения — SharedPreferences `migrated_facts_v1`.
 */
class LegacyFactsMigration(private val repo: FactsRepository) {

    data class Report(
        val ran: Boolean,
        val scanned: Int = 0,
        val migrated: Int = 0,
        val skippedSeed: Int = 0,
        val skippedSecret: Int = 0,
        val skippedDuplicate: Int = 0,
        val skippedInvalid: Int = 0,
    )

    fun migrateIfNeeded(context: Context): Report {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(FactPolicies.MIGRATED_FLAG, false)) return Report(ran = false)

        val legacyFile = context.getDatabasePath(FactPolicies.LEGACY_DB_NAME)
        if (!legacyFile.exists()) {
            prefs.edit().putBoolean(FactPolicies.MIGRATED_FLAG, true).apply()
            Log.i(TAG, "legacy db absent; nothing to migrate")
            return Report(ran = false)
        }

        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                legacyFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            val report = copy(db)
            prefs.edit().putBoolean(FactPolicies.MIGRATED_FLAG, true).apply()
            Log.i(
                TAG,
                "legacy facts migrated scanned=${report.scanned} ok=${report.migrated} " +
                    "seed=${report.skippedSeed} secret=${report.skippedSecret} " +
                    "dup=${report.skippedDuplicate} invalid=${report.skippedInvalid}",
            )
            report
        } catch (t: Throwable) {
            // Флаг не ставим: попробуем ещё раз при следующем запуске.
            Log.w(TAG, "legacy facts migration failed: ${t.message}")
            Report(ran = true)
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun copy(legacy: SQLiteDatabase): Report {
        var scanned = 0
        var migrated = 0
        var seed = 0
        var secret = 0
        var dup = 0
        var invalid = 0
        legacy.rawQuery(
            "SELECT text, category, source, created_at FROM facts ORDER BY created_at ASC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                scanned++
                val text = c.getString(0).orEmpty()
                val category = c.getString(1)?.takeIf { it.isNotBlank() } ?: "general"
                val source = c.getString(2)?.takeIf { it.isNotBlank() } ?: "user"
                val createdAt = if (c.isNull(3)) System.currentTimeMillis() else c.getLong(3)
                if (!FactPolicies.shouldMigrateSource(source)) { seed++; continue }
                if (!FactPolicies.isStorable(text)) { invalid++; continue }
                if (FactPolicies.isSecret(text)) { secret++; continue }
                when (repo.add(text, category, source, createdAt)) {
                    FactAddResult.STORED -> migrated++
                    FactAddResult.DUPLICATE -> dup++
                    FactAddResult.REJECTED_SECRET -> secret++
                    FactAddResult.REJECTED_TOO_SHORT, FactAddResult.REJECTED_TOO_LONG -> invalid++
                }
            }
        }
        return Report(
            ran = true,
            scanned = scanned,
            migrated = migrated,
            skippedSeed = seed,
            skippedSecret = secret,
            skippedDuplicate = dup,
            skippedInvalid = invalid,
        )
    }

    companion object {
        private const val TAG = "Jarvis.MemoryMigration"

        /** То же имя, что использует остальное приложение. */
        const val PREFS: String = "jarvis_prefs"
    }
}
