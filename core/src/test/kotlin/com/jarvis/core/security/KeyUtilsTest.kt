package com.jarvis.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyUtilsTest {

    @Test
    fun testNormalizeProviderId() {
        org.junit.Assert.assertEquals("groq", KeyUtils.normalizeProviderId("  Groq  "))
        org.junit.Assert.assertEquals("custom", KeyUtils.normalizeProviderId("CUSTOM"))
    }

    @Test
    fun testCleanKey() {
        org.junit.Assert.assertEquals("sk-proj-1234567890abcdef", KeyUtils.cleanKey("  \"sk-proj-1234567890abcdef\"  "))
        org.junit.Assert.assertEquals("sk-proj-1234567890abcdef", KeyUtils.cleanKey(" 'sk-proj-1234567890abcdef' "))
        org.junit.Assert.assertEquals("sk-proj-1234567890abcdef", KeyUtils.cleanKey("sk-proj-1234567890abcdef"))
    }

    @Test
    fun testIsPlaceholderKey_blank() {
        assertTrue(KeyUtils.isPlaceholderKey(null))
        assertTrue(KeyUtils.isPlaceholderKey(""))
        assertTrue(KeyUtils.isPlaceholderKey("   "))
    }

    @Test
    fun testIsPlaceholderKey_dots() {
        assertTrue(KeyUtils.isPlaceholderKey("..."))
    }

    @Test
    fun testIsPlaceholderKey_brackets() {
        assertTrue(KeyUtils.isPlaceholderKey("<your-key-here>"))
        assertTrue(KeyUtils.isPlaceholderKey("sk-proj-<abc>"))
    }

    @Test
    fun testIsPlaceholderKey_tooShort() {
        assertTrue(KeyUtils.isPlaceholderKey("shortkey123"))
    }

    @Test
    fun testIsPlaceholderKey_valid() {
        assertFalse(KeyUtils.isPlaceholderKey("sk-proj-1234567890abcdefghijklmnop"))
    }
}
