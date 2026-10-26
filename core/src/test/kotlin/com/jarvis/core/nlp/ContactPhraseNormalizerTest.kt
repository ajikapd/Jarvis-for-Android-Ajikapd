package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPhraseNormalizerTest {

    @Test
    fun `любимой жене becomes жена for search`() {
        val q = ContactPhraseNormalizer.searchQueries("любимой жене")
        assertEquals("жена", q.first())
        assertTrue(q.contains("любимая жена"))
        assertFalse(q.first() == "любимой жене")
    }

    @Test
    fun `маме becomes мама`() {
        assertEquals("мама", ContactPhraseNormalizer.canonical("маме"))
    }

    @Test
    fun `ивану becomes иван`() {
        assertEquals("иван", ContactPhraseNormalizer.canonical("ивану").lowercase())
    }
}
