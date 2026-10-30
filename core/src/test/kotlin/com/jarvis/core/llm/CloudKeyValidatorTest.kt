package com.jarvis.core.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyValidatorTest {
    @Test
    fun normalizeProviderId_trimsLowercasesAndHandlesNull() {
        assertEquals("groq", CloudKeyValidator.normalizeProviderId("  Groq  "))
        assertEquals("custom_asr", CloudKeyValidator.normalizeProviderId(" CUSTOM_ASR "))
        assertEquals("", CloudKeyValidator.normalizeProviderId(null))
    }

    @Test
    fun normalizeProviderId_blankIsEmpty() {
        assertEquals("", CloudKeyValidator.normalizeProviderId(" \t "))
    }

    @Test
    fun sanitizeKey_removesWhitespace() {
        assertEquals("abc123xyz789", CloudKeyValidator.sanitizeKey("  abc123xyz789  "))
    }

    @Test
    fun sanitizeKey_removesDoubleQuotes() {
        assertEquals("mykey", CloudKeyValidator.sanitizeKey("\"mykey\""))
    }

    @Test
    fun sanitizeKey_removesSingleQuotes() {
        assertEquals("mykey", CloudKeyValidator.sanitizeKey("'mykey'"))
    }

    @Test
    fun sanitizeKey_removesLineBreaks() {
        assertEquals("keywithnewline", CloudKeyValidator.sanitizeKey("keywith\nnewline\r"))
    }

    @Test
    fun sanitizeKey_keepsInternalSpaces() {
        assertEquals("abc def 01234567890123", CloudKeyValidator.sanitizeKey("abc def 01234567890123"))
    }

    @Test
    fun isStubKey_rejectsEmptyNullWhitespaceAndDots() {
        assertTrue(CloudKeyValidator.isStubKey(null))
        assertTrue(CloudKeyValidator.isStubKey(""))
        assertTrue(CloudKeyValidator.isStubKey("   "))
        assertTrue(CloudKeyValidator.isStubKey("..."))
    }

    @Test
    fun isStubKey_rejectsAngleBracketPlaceholders() {
        assertTrue(CloudKeyValidator.isStubKey("<enter_key_here>"))
        assertTrue(CloudKeyValidator.isStubKey("abcdefghijklmnop<placeholder>"))
    }

    @Test
    fun isStubKey_rejectsShortValues() {
        assertTrue(CloudKeyValidator.isStubKey("shortkey123"))
    }

    @Test
    fun isStubKey_acceptsTwentyCharacters() {
        assertFalse(CloudKeyValidator.isStubKey("12345678901234567890"))
    }

    @Test
    fun isStubKey_acceptsLongProviderKey() {
        assertFalse(CloudKeyValidator.isStubKey("sk-ant-api03-1234567890abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun envelope_roundTripsBytes() {
        val encoded = CloudKeyValidator.encodeEnvelope(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val decoded = CloudKeyValidator.decodeEnvelope(encoded)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded!!.first)
        assertArrayEquals(byteArrayOf(4, 5, 6), decoded.second)
    }

    @Test
    fun envelope_rejectsNullMissingSeparatorAndEmptyParts() {
        assertNull(CloudKeyValidator.decodeEnvelope(null))
        assertNull(CloudKeyValidator.decodeEnvelope("YWJj"))
        assertNull(CloudKeyValidator.decodeEnvelope("YWJj:"))
    }

    @Test
    fun envelope_rejectsInvalidBase64() {
        assertNull(CloudKeyValidator.decodeEnvelope("not-base64:also-not-base64"))
    }
}

