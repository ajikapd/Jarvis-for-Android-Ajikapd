package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuEnTextMergerTest {

    @Test
    fun `music command takes english title from en stream`() {
        val out = RuEnTextMerger.merge("включи песню фазер", "father")
        assertEquals("включи песню father", out)
    }

    @Test
    fun `andy warhol from en stream`() {
        val out = RuEnTextMerger.merge("включи песню энди ворхол", "andy warhol")
        assertTrue(out.contains("andy"))
        assertTrue(out.contains("warhol"))
        assertTrue(out.startsWith("включи песню"))
    }

    @Test
    fun `open app merges en name`() {
        val out = RuEnTextMerger.merge("открой инси", "incy")
        assertEquals("открой incy", out)
    }

    @Test
    fun `falls back to ru normalize when en empty`() {
        assertEquals("включи песню father", RuEnTextMerger.merge("включи песню фазер", ""))
    }
}
