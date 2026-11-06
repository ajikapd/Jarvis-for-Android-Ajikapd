package com.jarvis.core.nlp

/** Именованный сценарий: несколько обычных команд и одна итоговая реплика. */
data class Routine(
    val steps: List<String>,
    val closing: String,
    /** Команда, чей ответ произносится перед итоговой репликой. */
    val report: String? = null,
)

object Routines {
    private val SLEEP = Routine(
        listOf("останови музыку", "выключи фонарик", "яркость на минимум", "включи режим не беспокоить"),
        "Спокойной ночи, сэр.",
    )
    private val MOVIE = Routine(
        listOf("останови музыку", "яркость на тридцать", "включи режим не беспокоить"),
        "Приятного просмотра, сэр.",
    )
    private val FOCUS = Routine(listOf("останови музыку", "включи режим не беспокоить"), "Вас не потревожат, сэр.")
    private val LEAVING = Routine(listOf("останови музыку", "выключи фонарик"), "Хорошего пути, сэр.", report = "погода")
    private val MORNING = Routine(listOf("выключи режим не беспокоить", "яркость на шестьдесят", "сводка на день"), "")

    private val PHRASES = mapOf(
        "режим сна" to SLEEP, "я ложусь спать" to SLEEP, "иду спать" to SLEEP, "пора спать" to SLEEP, "я спать" to SLEEP,
        "режим кино" to MOVIE, "киносеанс" to MOVIE, "смотрю фильм" to MOVIE,
        "режим работы" to FOCUS, "режим концентрации" to FOCUS, "мне нужно сосредоточиться" to FOCUS,
        "я ухожу" to LEAVING, "я ушёл" to LEAVING, "я ушел" to LEAVING, "режим ухода" to LEAVING,
        "я проснулся" to MORNING, "я встал" to MORNING, "режим дня" to MORNING,
    )

    /** Только фраза целиком: «я ухожу через час» сценарием не считается. */
    fun find(text: String): Routine? {
        val spoken = text.lowercase().trim().trimEnd('.', '!', '?', '…').removePrefix("джарвис").trim(' ', ',')
        return PHRASES[spoken.removeSuffix(", сэр").removeSuffix(" пожалуйста").trim(' ', ',')]
    }
}
