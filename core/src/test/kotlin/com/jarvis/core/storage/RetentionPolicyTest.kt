package com.jarvis.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 1/4: политика сроков хранения и загрузки истории.
 */
class RetentionPolicyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun limitsMatchContract() {
        assertEquals(90L * 24 * 60 * 60 * 1000, RetentionPolicy.CONVERSATION_TTL_MS)
        assertEquals(48L * 60 * 60 * 1000, RetentionPolicy.RECENT_WINDOW_MS)
        assertEquals(20, RetentionPolicy.CONTEXT_RECENT_LIMIT)
        assertEquals(60, RetentionPolicy.STORE_RECENT_LIMIT)
    }

    @Test
    fun oldMessageExpiresButKeepSurvives() {
        val old = now - RetentionPolicy.CONVERSATION_TTL_MS - 1
        assertTrue(RetentionPolicy.isExpired(old, now, keep = false))
        assertFalse(RetentionPolicy.isExpired(old, now, keep = true))
    }

    @Test
    fun freshMessageIsNotExpired() {
        val fresh = now - 1000L
        assertFalse(RetentionPolicy.isExpired(fresh, now, keep = false))
    }

    @Test
    fun boundaryIsExactlyNinetyDays() {
        val exactly = now - RetentionPolicy.CONVERSATION_TTL_MS
        assertFalse("record exactly at TTL is still kept", RetentionPolicy.isExpired(exactly, now, keep = false))
        assertTrue(RetentionPolicy.isExpired(exactly - 1, now, keep = false))
    }

    @Test
    fun recentWindowIsFortyEightHours() {
        assertTrue(RetentionPolicy.isRecent(now - RetentionPolicy.RECENT_WINDOW_MS + 1, now))
        assertTrue(RetentionPolicy.isRecent(now - RetentionPolicy.RECENT_WINDOW_MS, now))
        assertFalse(RetentionPolicy.isRecent(now - RetentionPolicy.RECENT_WINDOW_MS - 1, now))
    }

    @Test
    fun futureTimestampCountsAsRecent() {
        // Часы устройства могли уйти назад — запись «из будущего» не теряем.
        assertTrue(RetentionPolicy.isRecent(now + 5_000, now))
    }

    @Test
    fun factExpiry() {
        assertFalse(RetentionPolicy.isFactExpired(null, now))
        assertFalse(RetentionPolicy.isFactExpired(now + 1, now))
        assertTrue(RetentionPolicy.isFactExpired(now, now))
        assertTrue(RetentionPolicy.isFactExpired(now - 1, now))
    }

    @Test
    fun cutoffShiftsWithTtl() {
        assertEquals(now - RetentionPolicy.CONVERSATION_TTL_MS, RetentionPolicy.cutoff(now))
        assertEquals(now - 1000L, RetentionPolicy.cutoff(now, ttlMs = 1000L))
    }

    @Test
    fun ageHandlesMissingAndFutureTimestamps() {
        assertEquals(500L, RetentionPolicy.ageMs(now - 500L, now))
        assertEquals(0L, RetentionPolicy.ageMs(now + 500L, now))
        assertEquals(-1L, RetentionPolicy.ageMs(0L, now))
    }
}
