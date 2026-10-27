package com.jarvis.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 4: одна реплика, пришедшая из brain и из UI, пишется в БД один раз.
 */
class ConversationDedupeTest {

    private val now = 1_800_000_000_000L

    @Test
    fun sameRoleAndTextWithinWindowIsDuplicate() {
        assertTrue(
            ConversationDedupe.isDuplicate(
                previousRole = "assistant",
                previousText = "Пользователь любит чай",
                previousAt = now - 100,
                role = "assistant",
                text = "Пользователь любит чай",
                nowMillis = now,
            ),
        )
    }

    @Test
    fun differentRoleIsNotDuplicate() {
        assertFalse(
            ConversationDedupe.isDuplicate("user", "привет", now - 100, "assistant", "привет", now),
        )
    }

    @Test
    fun differentTextIsNotDuplicate() {
        assertFalse(
            ConversationDedupe.isDuplicate("user", "привет", now - 100, "user", "привет!", now),
        )
    }

    @Test
    fun outsideWindowIsNotDuplicate() {
        assertFalse(
            ConversationDedupe.isDuplicate(
                "user", "привет", now - ConversationDedupe.WINDOW_MS - 1, "user", "привет", now,
            ),
        )
    }

    @Test
    fun firstMessageIsNeverDuplicate() {
        assertFalse(ConversationDedupe.isDuplicate(null, null, 0L, "user", "привет", now))
    }

    @Test
    fun futureTimestampIsNotTreatedAsDuplicate() {
        // Часы ушли назад: не склеиваем разные реплики.
        assertFalse(ConversationDedupe.isDuplicate("user", "привет", now + 5_000, "user", "привет", now))
    }

    @Test
    fun customWindowIsRespected() {
        assertTrue(ConversationDedupe.isDuplicate("user", "x", now - 500, "user", "x", now, windowMs = 1_000))
        assertFalse(ConversationDedupe.isDuplicate("user", "x", now - 500, "user", "x", now, windowMs = 100))
    }
}
