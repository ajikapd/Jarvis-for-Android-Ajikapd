package com.jarvis.core.storage

/**
 * Схема постоянного хранилища `jarvis_core.db`.
 *
 * Чистый Kotlin (без Android): DDL и каркас миграций проверяются JVM-тестами,
 * а [JarvisDb] лишь исполняет эти строки.
 *
 * Правило версий: [VERSION] — текущая версия схемы. Каждое изменение схемы
 * добавляет в [MIGRATIONS] шаг с `targetVersion = предыдущая + 1`, который
 * приводит БД с этой предыдущей версии к новой. `onCreate` создаёт сразу
 * актуальную схему, `onUpgrade` последовательно применяет недостающие шаги —
 * так обе ветки (новая установка и апгрейд) заканчиваются одним DDL.
 */
object JarvisSchema {

    const val DB_NAME: String = "jarvis_core.db"

    /** Версия схемы. Меняется только вместе с новой записью в [MIGRATIONS]. */
    const val VERSION: Int = 1

    const val TABLE_FACTS: String = "facts"
    const val TABLE_MESSAGES: String = "conversation_messages"

    const val ROLE_USER: String = "user"
    const val ROLE_ASSISTANT: String = "assistant"

    /**
     * Один шаг миграции: SQL, переводящий схему с `targetVersion - 1` на
     * `targetVersion`. Выполняется ровно один раз на устройстве.
     */
    data class Migration(
        val targetVersion: Int,
        val statements: List<String>,
    )

    /**
     * v1 создаётся в `onCreate`, поэтому реальных шагов апгрейда пока нет.
     * Следующее изменение схемы обязано добавить сюда `Migration(2, ...)`
     * и поднять [VERSION].
     */
    val MIGRATIONS: List<Migration> = emptyList()

    /** DDL актуальной схемы (используется в `onCreate`). */
    val CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE facts (
            id TEXT PRIMARY KEY NOT NULL,
            text TEXT NOT NULL,
            category TEXT NOT NULL,
            source TEXT NOT NULL,
            weight REAL NOT NULL DEFAULT 1,
            sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            expires_at INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX idx_facts_category ON facts(category)",
        "CREATE INDEX idx_facts_updated_at ON facts(updated_at)",
        """
        CREATE TABLE conversation_messages (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            text TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            keep INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE INDEX idx_messages_created_at ON conversation_messages(created_at)",
    )

    /**
     * Какие шаги нужно применить при переходе со [oldVersion] на [newVersion].
     *
     * Возвращает целевые версии по возрастанию, только те, что попадают в
     * полуинтервал `(oldVersion, newVersion]`. Пустой список — апгрейд не нужен
     * (или БД новее, чем мы умеем: тогда вызывающий падает с понятной ошибкой).
     */
    fun planUpgrade(
        oldVersion: Int,
        newVersion: Int,
        migrations: List<Migration> = MIGRATIONS,
    ): List<Int> = migrations
        .map { it.targetVersion }
        .filter { it > oldVersion && it <= newVersion }
        .distinct()
        .sorted()

    /** SQL шага [targetVersion]. Неизвестная версия — ошибка, а не тихий пропуск. */
    fun statementsFor(
        targetVersion: Int,
        migrations: List<Migration> = MIGRATIONS,
    ): List<String> {
        val step = migrations.firstOrNull { it.targetVersion == targetVersion }
            ?: throw IllegalArgumentException("no migration to schema v$targetVersion")
        return step.statements
    }

    /** Проверка целостности каркаса: версии строго по возрастанию, без дыр. */
    fun isContiguous(migrations: List<Migration>): Boolean {
        if (migrations.isEmpty()) return VERSION == 1
        var expected = 2
        for (m in migrations) {
            if (m.targetVersion != expected) return false
            if (m.statements.isEmpty()) return false
            expected++
        }
        return VERSION == migrations.last().targetVersion
    }
}
