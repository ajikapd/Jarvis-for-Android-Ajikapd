package com.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundCommandSplitterTest {

    @Test
    fun `splits open click and type`() {
        val steps = CompoundCommandSplitter.split(
            "открой telegram и нажми мама и напиши я выезжаю",
        )
        assertEquals(3, steps.size)
        assertTrue(steps[0].contains("открой"))
        assertTrue(steps[1].contains("нажми"))
        assertTrue(steps[2].contains("напиши"))
    }

    @Test
    fun `splits by comma and потом`() {
        val steps = CompoundCommandSplitter.split("открой настройки, потом нажми wi-fi")
        assertEquals(2, steps.size)
    }

    @Test
    fun `splits open and what on screen`() {
        val steps = CompoundCommandSplitter.split("открой часы и что на экране")
        assertEquals(2, steps.size)
        assertTrue(steps[0].startsWith("открой"))
        assertTrue(steps[1].contains("что на экране"))
    }

    @Test
    fun `splits open settings and tap search`() {
        val steps = CompoundCommandSplitter.split("открой настройки и нажми поиск")
        assertEquals(2, steps.size)
        assertTrue(steps[1].contains("нажми"))
    }
}
