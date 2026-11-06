package com.jarvis.core.reminder

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** [command] true: в срок выполняется [text] как голосовая команда, а не зачитывается. */
data class Reminder(
    val id: Long,
    val at: LocalDateTime,
    val text: String,
    val daily: Boolean = false,
    val command: Boolean = false,
)

/** Хранилище и будильник напоминаний; реализует приложение. */
interface ReminderBook {
    fun add(at: LocalDateTime, text: String, daily: Boolean, command: Boolean = false): Reminder
    fun all(): List<Reminder>
    fun remove(id: Long): Boolean
}

object ReminderSpeech {
    private val WEEKDAY_FORMS = mapOf(1 to "в понедельник", 2 to "во вторник", 3 to "в среду", 4 to "в четверг",
        5 to "в пятницу", 6 to "в субботу", 7 to "в воскресенье")

    /** Когда сработает: «через 20 минут», «завтра в 9:00», «в пятницу в 18:30». */
    fun whenPhrase(at: LocalDateTime, now: LocalDateTime): String {
        val minutes = Duration.between(now, at).toMinutes()
        if (minutes in 0..59 && at.toLocalDate() == now.toLocalDate()) {
            return if (minutes == 0L) "через минуту" else "через $minutes мин"
        }
        val time = "в %d:%02d".format(at.hour, at.minute)
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val day = when {
            days == 0L -> "сегодня"
            days == 1L -> "завтра"
            days == 2L -> "послезавтра"
            days in 3..6 -> WEEKDAY_FORMS.getValue(at.dayOfWeek.value)
            else -> "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.FULL, Locale("ru"))}"
        }
        return "$day $time"
    }

    fun weekdayPhrase(date: java.time.LocalDate): String = WEEKDAY_FORMS.getValue(date.dayOfWeek.value)

    fun firing(text: String): String =
        if (text.isBlank()) "Сэр, вы просили напомнить в это время." else "Напоминаю, сэр: $text."
}
