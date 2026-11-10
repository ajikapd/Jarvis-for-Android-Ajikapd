package com.jarvis.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFitTest {
    @Test
    fun `short prompt stays as is`() {
        val f = PromptFit.fit("система", "вопрос", 512, 22)
        assertEquals("система", f.system)
        assertEquals("вопрос", f.prompt)
    }

    @Test
    fun `long system is cut so everything fits the context`() {
        val f = PromptFit.fit("с".repeat(5000), "какая сегодня погода", 512, 22)
        assertEquals("какая сегодня погода", f.prompt)
        assertTrue(f.system.length + f.prompt.length <= PromptFit.maxChars(512, 22))
    }

    @Test
    fun `even a huge prompt never exceeds the limit and keeps its end`() {
        val f = PromptFit.fit("система", "x".repeat(10_000) + "вопрос", 384, 18)
        assertEquals("", f.system)
        assertTrue(f.prompt.endsWith("вопрос"))
        assertTrue(f.prompt.length <= PromptFit.maxChars(384, 18))
    }

    @Test
    fun `limit is well below context in tokens`() {
        // 2.2 символа на токен: 466 токенов места — не больше ~1025 символов.
        assertTrue(PromptFit.maxChars(512, 22) <= (512 - 22) * 2.2)
    }
}
