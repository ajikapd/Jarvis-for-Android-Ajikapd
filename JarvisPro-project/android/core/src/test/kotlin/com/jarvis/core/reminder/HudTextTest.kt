package com.jarvis.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class HudTextTest {
    private val now = LocalDateTime.of(2026, 11, 5, 14, 10)

    @Test fun nearestFutureWins() = assertEquals(
        "Далее: завтра в 9:00 · позвонить",
        HudText.next(
            listOf(
                Reminder(1, now.minusHours(1), "прошло"),
                Reminder(2, now.plusDays(5), "потом"),
                Reminder(3, LocalDateTime.of(2026, 11, 6, 9, 0), "позвонить"),
            ),
            now,
        ),
    )

    @Test fun none() = assertEquals("Напоминаний нет", HudText.next(emptyList(), now))
    @Test fun status() = assertEquals("Слушаю · заряд 80%", HudText.status(true, 80))
}
