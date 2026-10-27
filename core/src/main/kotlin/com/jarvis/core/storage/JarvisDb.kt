package com.jarvis.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Единственный SQLiteOpenHelper постоянной памяти (`jarvis_core.db`).
 *
 * - WAL + `PRAGMA foreign_keys=ON` включаются до первого открытия;
 * - `onCreate` создаёт актуальную схему, `onUpgrade` последовательно применяет
 *   недостающие шаги из [JarvisSchema.MIGRATIONS] (каркас migrateTo2, migrateTo3…
 *   описан в [JarvisSchema.planUpgrade]);
 * - экземпляр один на процесс: открытие БД дорогое, а соединение потокобезопасно.
 */
class JarvisDb private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    JarvisSchema.DB_NAME,
    null,
    JarvisSchema.VERSION,
) {

    init {
        // Должно быть вызвано до getWritableDatabase().
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val startedAt = System.currentTimeMillis()
        JarvisSchema.CREATE_STATEMENTS.forEach { db.execSQL(it) }
        Log.i(TAG, "schema created v${JarvisSchema.VERSION} in ${System.currentTimeMillis() - startedAt}ms")
    }

    /**
     * Каскад миграций. Каждый шаг применяется ровно один раз и только если он
     * нужен для перехода `oldVersion → newVersion`.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion > JarvisSchema.VERSION) {
            error("db schema v$newVersion is newer than app schema v${JarvisSchema.VERSION}")
        }
        val plan = JarvisSchema.planUpgrade(oldVersion, newVersion)
        Log.i(TAG, "upgrade v$oldVersion -> v$newVersion plan=$plan")
        for (target in plan) {
            JarvisSchema.statementsFor(target).forEach { db.execSQL(it) }
        }
    }

    companion object {
        private const val TAG = "Jarvis.Db"

        @Volatile
        private var instance: JarvisDb? = null

        fun get(context: Context): JarvisDb =
            instance ?: synchronized(this) {
                instance ?: JarvisDb(context).also { instance = it }
            }

        /** Только для тестов/диагностики: закрыть и забыть соединение. */
        fun resetForTests() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }
    }
}
