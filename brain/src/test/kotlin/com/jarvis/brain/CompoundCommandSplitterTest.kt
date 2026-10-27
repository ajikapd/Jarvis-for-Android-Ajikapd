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

    // ───────────────────────────── А4: режем только команды

    @Test
    fun `small talk with comma stays one request`() {
        val phrase = "Привет, как дела?"
        assertEquals(listOf(phrase), CompoundCommandSplitter.split(phrase))
    }

    @Test
    fun `polite lead-in before a command stays one request`() {
        val phrase = "Слушай, включи свет"
        assertEquals(listOf(phrase), CompoundCommandSplitter.split(phrase))
    }

    @Test
    fun `politeness tail stays one request`() {
        val phrase = "Включи свет, пожалуйста"
        assertEquals(listOf(phrase), CompoundCommandSplitter.split(phrase))
    }

    @Test
    fun `chat question after comma stays one request`() {
        val phrase = "Расскажи, что ты умеешь"
        assertEquals(listOf(phrase), CompoundCommandSplitter.split(phrase))
    }

    @Test
    fun `two commands joined by and are split`() {
        val steps = CompoundCommandSplitter.split("Включи фонарик и поставь таймер на пять минут")
        assertEquals(2, steps.size)
        assertTrue(steps[0].startsWith("Включи"))
        assertTrue(steps[1].startsWith("поставь"))
    }

    @Test
    fun `two commands joined by comma and потом are split`() {
        val steps = CompoundCommandSplitter.split("Включи свет, потом выключи музыку")
        assertEquals(2, steps.size)
        assertTrue(steps[0].startsWith("Включи"))
        assertTrue(steps[1].startsWith("выключи"))
    }

    @Test
    fun `fact sentence with comma stays one request`() {
        val phrase = "Меня зовут Артём, я живу в Москве"
        assertEquals(listOf(phrase), CompoundCommandSplitter.split(phrase))
    }

    @Test
    fun `volume triggers are commands`() {
        val steps = CompoundCommandSplitter.split("громче, потом тише")
        assertEquals(2, steps.size)
    }
}
