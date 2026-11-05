package com.jarvis.core.nlp

/** Манера Джарвиса в коротких подтверждениях: инструменты отвечают сухо, а говорить должен дворецкий. */
object JarvisStyle {
    private const val MAX_CONFIRMATION = 48

    private val PHRASES = mapOf(
        "Включён." to "Фонарик включён, сэр.",
        "Выключен." to "Фонарик выключен, сэр.",
        "Открыл." to "Открываю, сэр.",
        "Играю." to "Воспроизвожу, сэр.",
        "Дальше." to "Следующий трек, сэр.",
        "Назад." to "Предыдущий трек, сэр.",
        "Максимум." to "Громкость на максимуме, сэр.",
        "Без звука." to "Звук отключён, сэр.",
    )

    private val LISTENING = listOf("Слушаю, сэр.", "Да, сэр?", "К вашим услугам.", "Слушаю вас, сэр.")

    /** Короткое подтверждение действия превращается в реплику, всё остальное не трогаем. */
    fun confirmation(text: String): String {
        val t = text.trim()
        PHRASES[t]?.let { return it }
        if (t.length > MAX_CONFIRMATION || !t.endsWith(".") || t.contains("сэр", ignoreCase = true)) return t
        return t.dropLast(1) + ", сэр."
    }

    fun listening(turn: Int): String = LISTENING[Math.floorMod(turn, LISTENING.size)]
}
