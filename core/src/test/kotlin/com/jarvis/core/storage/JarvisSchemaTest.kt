package com.jarvis.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 1: каркас миграций и DDL. Проверяется без Android SQLite.
 */
class JarvisSchemaTest {

    @Test
    fun currentVersionIsOne() {
        assertEquals(1, JarvisSchema.VERSION)
        assertEquals("jarvis_core.db", JarvisSchema.DB_NAME)
    }

    @Test
    fun migrationStepsAreOrderedAndCoverEveryVersion() {
        var previous = 1
        JarvisSchema.MIGRATIONS.forEach { step ->
            assertEquals("migration versions must be contiguous", previous + 1, step.targetVersion)
            assertTrue("migration v${step.targetVersion} must have SQL", step.statements.isNotEmpty())
            previous = step.targetVersion
        }
        assertEquals("last migration must reach VERSION", JarvisSchema.VERSION, previous)
        assertTrue(JarvisSchema.isContiguous(JarvisSchema.MIGRATIONS))
    }

    @Test
    fun planUpgradeFromOldToNewIsSequential() {
        val steps = listOf(
            JarvisSchema.Migration(2, listOf("ALTER TABLE facts ADD COLUMN x TEXT")),
            JarvisSchema.Migration(3, listOf("ALTER TABLE facts ADD COLUMN y TEXT")),
        )
        assertEquals(listOf(2, 3), JarvisSchema.planUpgrade(1, 3, steps))
        assertEquals(listOf(3), JarvisSchema.planUpgrade(2, 3, steps))
        assertEquals(emptyList<Int>(), JarvisSchema.planUpgrade(3, 3, steps))
        assertEquals(emptyList<Int>(), JarvisSchema.planUpgrade(1, 1, steps))
    }

    @Test
    fun planUpgradeNeverSkipsIntermediateVersions() {
        val steps = listOf(
            JarvisSchema.Migration(2, listOf("sql2")),
            JarvisSchema.Migration(3, listOf("sql3")),
            JarvisSchema.Migration(4, listOf("sql4")),
        )
        val plan = JarvisSchema.planUpgrade(1, 4, steps)
        assertEquals(listOf(2, 3, 4), plan)
        plan.forEach { assertTrue(JarvisSchema.statementsFor(it, steps).isNotEmpty()) }
    }

    @Test
    fun unknownMigrationFailsLoudly() {
        val error = runCatching { JarvisSchema.statementsFor(99, JarvisSchema.MIGRATIONS) }
        assertTrue(error.isFailure)
    }

    @Test
    fun brokenFrameworkIsDetected() {
        val withGap = listOf(
            JarvisSchema.Migration(2, listOf("sql2")),
            JarvisSchema.Migration(4, listOf("sql4")),
        )
        assertFalse(JarvisSchema.isContiguous(withGap))

        val withEmptySql = listOf(JarvisSchema.Migration(2, emptyList()))
        assertFalse(JarvisSchema.isContiguous(withEmptySql))
    }

    @Test
    fun ddlCreatesBothTablesAndAllColumns() {
        val ddl = JarvisSchema.CREATE_STATEMENTS.joinToString("\n")
        assertTrue(ddl.contains("CREATE TABLE facts"))
        for (column in listOf(
            "id TEXT PRIMARY KEY NOT NULL",
            "text TEXT NOT NULL",
            "category TEXT NOT NULL",
            "source TEXT NOT NULL",
            "weight REAL NOT NULL DEFAULT 1",
            "sensitivity TEXT NOT NULL DEFAULT 'NORMAL'",
            "created_at INTEGER NOT NULL",
            "updated_at INTEGER NOT NULL",
            "expires_at INTEGER",
        )) {
            assertTrue("facts is missing: $column", ddl.contains(column))
        }
        assertTrue(ddl.contains("CREATE INDEX idx_facts_category ON facts(category)"))
        assertTrue(ddl.contains("CREATE INDEX idx_facts_updated_at ON facts(updated_at)"))

        assertTrue(ddl.contains("CREATE TABLE conversation_messages"))
        for (column in listOf(
            "id TEXT PRIMARY KEY NOT NULL",
            "session_id TEXT NOT NULL",
            "role TEXT NOT NULL",
            "text TEXT NOT NULL",
            "created_at INTEGER NOT NULL",
            "keep INTEGER NOT NULL DEFAULT 0",
        )) {
            assertTrue("conversation_messages is missing: $column", ddl.contains(column))
        }
        assertTrue(ddl.contains("CREATE INDEX idx_messages_created_at ON conversation_messages(created_at)"))
    }
}
