package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTextSelectorTest {

    @Test
    fun `music uses en latin even when multi stays cyrillic`() {
        val out = CommandTextSelector.select(
            streamingRu = "включи песню фазер",
            offlineMulti = "включи песню фазер",
            englishPass = "father",
        )
        assertEquals("включи песню father", out)
    }

    @Test
    fun `andy warhol from en pass`() {
        val out = CommandTextSelector.select(
            streamingRu = "включи песню энди ворхол",
            offlineMulti = "включи песню энди ворхол",
            englishPass = "and he warhol", // noisy EN — still has warhol
        )
        assertTrue(out.contains("warhol"))
        assertTrue(out.startsWith("включи песню"))
    }

    @Test
    fun `pure english offline still wins`() {
        val out = CommandTextSelector.select(
            streamingRu = "ай лав",
            offlineMulti = "I love you",
            englishPass = "i love you",
        )
        assertTrue(out.lowercase().contains("love"))
    }

    @Test
    fun `falls back to ru when en blank`() {
        assertEquals("включи свет", CommandTextSelector.select("включи свет", null, null))
    }

    @Test
    fun `extract latin drops noise words`() {
        assertEquals(
            "father",
            CommandTextSelector.extractLatinPhrase("uh the father yeah"),
        )
    }
}
