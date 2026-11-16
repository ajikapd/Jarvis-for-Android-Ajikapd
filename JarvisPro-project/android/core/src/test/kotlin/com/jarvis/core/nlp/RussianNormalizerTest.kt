package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianNormalizerTest {

    @Test
    fun `lowercases and trims`() {
        assertEquals("включи фонарик", RussianNormalizer.normalize("  Включи Фонарик  "))
    }

    @Test
    fun `strips punctuation but keeps words`() {
        assertEquals("включи фонарик", RussianNormalizer.normalize("«Включи фонарик!»"))
    }

    @Test
    fun `collapses multiple whitespace`() {
        assertEquals("включи фонарик", RussianNormalizer.normalize("включи   фонарик"))
    }

    @Test
    fun `splits into words`() {
        assertEquals(
            listOf("включи", "фонарик"),
            RussianNormalizer.normalizeWords("Включи, фонарик!")
        )
    }
}