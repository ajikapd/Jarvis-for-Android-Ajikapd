package com.jarvis.core.reminder

import java.time.LocalDateTime

/** Короткие строки для виджета и панели. */
object HudText {
    private const val MAX_TEXT = 40

    fun next(reminders: List<Reminder>, now: LocalDateTime): String {
        val nearest = reminders.filter { it.at > now }.minByOrNull { it.at } ?: return "Напоминаний нет"
        val moment = ReminderSpeech.whenPhrase(nearest.at, now)
        val what = nearest.text.take(MAX_TEXT)
        return if (what.isBlank()) "Далее: $moment" else "Далее: $moment · $what"
    }

    fun status(listening: Boolean, batteryPercent: Int): String =
        "${if (listening) "Слушаю" else "Отключён"} · заряд $batteryPercent%"
}
