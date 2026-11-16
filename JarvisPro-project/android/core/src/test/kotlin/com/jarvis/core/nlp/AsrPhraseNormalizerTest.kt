package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPhraseNormalizerTest {

    @Test
    fun `maps cyrillic english song name to latin`() {
        assertEquals("включи песню father", AsrPhraseNormalizer.normalize("включи песню фазер"))
    }

    @Test
    fun `maps multiword english artist phrase`() {
        assertEquals("включи andy warhol", AsrPhraseNormalizer.normalize("включи энди ворхол"))
    }

    @Test
    fun `maps cyrillic app name to latin`() {
        assertEquals("открой incy", AsrPhraseNormalizer.normalize("открой инси"))
    }

    @Test
    fun `keeps already latin tokens`() {
        assertEquals("открой telegram", AsrPhraseNormalizer.normalize("открой telegram"))
    }

    @Test
    fun `bare verb is incomplete`() {
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("включи"))
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("открой пожалуйста"))
        assertFalse(AsrPhraseNormalizer.isIncompleteCommand("включи свет"))
        assertFalse(AsrPhraseNormalizer.isIncompleteCommand("открой telegram"))
    }

    @Test
    fun `music frame without title is incomplete`() {
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("включи песню"))
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("поставь трек"))
        assertFalse(AsrPhraseNormalizer.isIncompleteCommand("включи песню father"))
        assertFalse(AsrPhraseNormalizer.isIncompleteCommand("включи песню фазер"))
    }

    @Test
    fun `truncated telegram write is incomplete`() {
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("напиши"))
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("напиши любимой жене"))
        assertTrue(AsrPhraseNormalizer.isIncompleteCommand("напиши любимой жене в"))
        assertFalse(AsrPhraseNormalizer.isIncompleteCommand("напиши любимой жене в телеграмме"))
        assertFalse(AsrPhraseNormalizer.isIncompleteCommand("напиши маме привет"))
    }
}