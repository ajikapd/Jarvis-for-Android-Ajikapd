package com.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeekSummaryTest {
    private val friday = LocalDate.of(2026, 11, 6)

    @Test fun `range and rainy days`() {
        val text = weekSummary("Москва", listOf(1.0, -2.0), listOf(4.0, 6.0), listOf(3, 61), friday)
        assertEquals("Москва на неделю: от -2° до 6°. Осадки завтра, сэр.", text)
    }

    @Test fun `dry week`() {
        val text = weekSummary("Сочи", listOf(10.0), listOf(15.0), listOf(1), friday)
        assertEquals("Сочи на неделю: от 10° до 15°. Без осадков, сэр.", text)
    }
}
