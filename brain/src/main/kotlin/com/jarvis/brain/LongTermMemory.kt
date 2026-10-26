package com.jarvis.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarvis.core.skills.SkillRetriever
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Долгая память (JARVIS_BRAIN_ARCHITECTURE §4.2).
 * SQLite-таблица facts(id, text, category, created_at, source, weight).
 * Поиск релевантных фактов — простой лексический score (позже заменится на embedding:
 * векторы будут лежать в отдельной BLOB-колонке, контракт пользования не поменяется).
 */
class LongTermMemory(context: Context) : LongTermMemoryInterface {

    private val lock = ReentrantLock()
    private val db by lazy { MemoryDbHelper(context).writableDatabase }

    /** Возвращает top-N фактов, релевантных запросу. */
    override fun retrieve(query: String, limit: Int): List<MemoryFact> = lock.withLock {
        val qWords = SkillRetriever.defaultNormalize(query)
        if (qWords.isEmpty()) return emptyList()

        all().map { fact ->
            val words = SkillRetriever.defaultNormalize(fact.text)
            FactScore(fact, score(qWords, words))
        }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.fact }
    }

    override fun add(text: String, category: String, source: String): Boolean = lock.withLock {
        val trimmed = text.trim()
        if (trimmed.length < 3) return false
        // не дублируем идентичный факт
        if (all().any { it.text.equals(trimmed, ignoreCase = true) }) return false

        val values = ContentValues().apply {
            put("text", trimmed)
            put("category", category)
            put("created_at", System.currentTimeMillis())
            put("source", source)
            put("weight", 1)
        }
        val id = db.insertOrThrow("facts", null, values)
        id > 0
    }

    override fun all(): List<MemoryFact> = lock.withLock {
        val list = mutableListOf<MemoryFact>()
        db.rawQuery("SELECT * FROM facts ORDER BY created_at DESC", null).use { c ->
            val idCol = c.getColumnIndexOrThrow("id")
            val textCol = c.getColumnIndexOrThrow("text")
            val catCol = c.getColumnIndexOrThrow("category")
            val createdCol = c.getColumnIndexOrThrow("created_at")
            val sourceCol = c.getColumnIndexOrThrow("source")
            while (c.moveToNext()) {
                list += MemoryFact(
                    id = c.getLong(idCol),
                    text = c.getString(textCol),
                    category = c.getString(catCol),
                    createdAt = c.getLong(createdCol),
                    source = c.getString(sourceCol)
                )
            }
        }
        list
    }

    override fun remove(id: Long): Boolean = lock.withLock {
        db.delete("facts", "id = ?", arrayOf(id.toString())) > 0
    }

    override fun clear() {
        lock.withLock { db.delete("facts", null, null) }
    }

    override fun count(): Int = lock.withLock {
        db.rawQuery("SELECT COUNT(*) FROM facts", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun score(qWords: List<String>, factWords: List<String>): Float {
        if (factWords.isEmpty()) return 0f
        val factSet = factWords.toSet()
        val hits = qWords.count { it in factSet }
        return hits.toFloat() / qWords.size
    }

    private data class FactScore(val fact: MemoryFact, val score: Float)

    data class MemoryFact(
        val id: Long,
        val text: String,
        val category: String,
        val createdAt: Long,
        val source: String
    )

    private class MemoryDbHelper(context: Context) : SQLiteOpenHelper(
        context, "jarvis_memory.db", null, 1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    text TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'general',
                    created_at INTEGER NOT NULL,
                    source TEXT NOT NULL DEFAULT 'user',
                    weight INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }
}

/**
 * Лёгкий экстрактор фактов (JARVIS_BRAIN_ARCHITECTURE §4.2): решает, стоит ли
 * запомнить устойчивый факт о пользователе из фразы. Ситуативные команды
 * ("включи фонарик") не запоминаются; указания предпочтений — да.
 */
object FactExtractor {

    private val factMarkers = listOf(
        "люблю", "предпочитаю", "ненавижу", "не люблю", "мне нравится",
        "мой", "моя", "моё", "мои", "у меня", "я обычно", "обычно я",
        "всегда", "никогда", "не хочу", "хочу", "напомни", "мой будильник",
        "мне не нравится", "поставь на", "я встаю в", "я ложусь в",
        "день рождения", "родился", "живу в", "работаю", "учусь в"
    )

    /**
     * @return текст факта одним предложением, или null если запоминать нечего.
     */
    fun extract(requestText: String): String? {
        val text = requestText.lowercase()
        val marker = factMarkers.firstOrNull { text.contains(it) } ?: return null
        val sentence = requestText.trim()
        return if (sentence.length in 3..240) sentence else null
    }
}