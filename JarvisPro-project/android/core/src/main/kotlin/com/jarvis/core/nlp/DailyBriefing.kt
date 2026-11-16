package com.jarvis.core.nlp

/** Сводка на день из уже полученных данных; недоступный раздел просто пропускается. */
object DailyBriefing {
    private const val NIGHT_END = 5
    private const val MORNING_END = 12
    private const val DAY_END = 18

    fun greeting(hour: Int): String = when {
        hour < NIGHT_END -> "Доброй ночи, сэр."
        hour < MORNING_END -> "Доброе утро, сэр."
        hour < DAY_END -> "Добрый день, сэр."
        else -> "Добрый вечер, сэр."
    }

    fun compose(hour: Int, minute: Int, weather: String?, events: String?, reminders: String?, status: String?): String {
        val parts = buildList {
            add(greeting(hour))
            add("Сейчас %d:%02d.".format(hour, minute))
            add(weather ?: "Погоду получить не удалось.")
            events?.let { add(it) }
            reminders?.takeUnless { it.startsWith("Напоминаний нет") }?.let { add(it) }
            status?.takeIf { it.startsWith("Внимание") }?.let { add(it.substringBefore(". ") + ".") }
        }
        return parts.joinToString(" ")
    }
}
