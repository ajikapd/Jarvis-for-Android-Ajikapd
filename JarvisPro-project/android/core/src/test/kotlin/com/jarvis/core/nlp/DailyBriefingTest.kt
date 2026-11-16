package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyBriefingTest {
    @Test fun full() = assertEquals(
        "Доброе утро, сэр. Сейчас 7:05. Москва, сейчас 4°. Ближайшие события: врач. " +
            "Напоминаний: 1. завтра: хлеб. Внимание, сэр: низкий заряд.",
        DailyBriefing.compose(
            7, 5, "Москва, сейчас 4°.", "Ближайшие события: врач.", "Напоминаний: 1. завтра: хлеб.",
            "Внимание, сэр: низкий заряд. Заряд 10%. Сеть: Wi-Fi.",
        ),
    )

    @Test fun healthyStatusAndNoRemindersAreSkipped() = assertEquals(
        "Добрый вечер, сэр. Сейчас 19:30. Погоду получить не удалось.",
        DailyBriefing.compose(19, 30, null, null, "Напоминаний нет, сэр.", "Все системы в норме, сэр. Заряд 80%."),
    )

    @Test fun greetings() {
        assertEquals("Доброй ночи, сэр.", DailyBriefing.greeting(3))
        assertEquals("Добрый день, сэр.", DailyBriefing.greeting(12))
    }
}
