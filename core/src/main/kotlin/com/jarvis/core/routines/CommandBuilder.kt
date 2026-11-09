package com.jarvis.core.routines

/**
 * Конструктор своих команд: фраза-триггер и что делать.
 * Цепочка — шаги по одному на строку, как обычные команды Джарвису.
 * Задание агенту — описание словами, выполняется как «сделай сам: …».
 */
object CommandBuilder {
    enum class Mode { CHAIN, AGENT }

    sealed class Result {
        data class Ok(val trigger: String, val steps: List<String>) : Result()
        data class Error(val message: String) : Result()
    }

    const val AGENT_PREFIX = "сделай сам: "
    private const val MAX_STEPS = 12

    /** Ключ фразы: регистр, «ё», обращение и вежливые хвосты не важны. */
    fun key(text: String): String =
        text.lowercase().replace('ё', 'е')
            .trim().trimEnd('.', '!', '?', '…')
            .removePrefix("джарвис").trim(' ', ',')
            .removeSuffix(", сэр").removeSuffix(" сэр").removeSuffix(" пожалуйста")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',')

    fun build(trigger: String, mode: Mode, body: String, reserved: Set<String> = emptySet()): Result {
        val name = key(trigger)
        if (name.length < 2) return Result.Error("Придумайте фразу, по которой Джарвис узнает команду.")
        if (name.split(' ').size > 8) return Result.Error("Фраза слишком длинная: до восьми слов.")
        if (name in reserved) return Result.Error("Фраза «$name» уже занята встроенной командой. Выберите другую.")
        val steps = when (mode) {
            Mode.CHAIN -> body.lines().map { it.trim().trimStart('-', '•', '*', ' ').trim() }.filter { it.isNotBlank() }
            Mode.AGENT -> body.trim().takeIf { it.isNotBlank() }?.let { listOf(AGENT_PREFIX + it.removePrefix(AGENT_PREFIX).trim()) }.orEmpty()
        }
        if (steps.isEmpty()) {
            return Result.Error(if (mode == Mode.CHAIN) "Добавьте хотя бы один шаг." else "Опишите, что должен сделать агент.")
        }
        if (steps.size > MAX_STEPS) return Result.Error("Слишком много шагов: до $MAX_STEPS.")
        if (steps.any { key(it) == name }) return Result.Error("Команда не может вызывать сама себя.")
        return Result.Ok(name, steps)
    }

    /** Как показать сохранённую команду в редакторе. */
    fun modeOf(steps: List<String>): Mode =
        if (steps.size == 1 && steps.first().startsWith(AGENT_PREFIX)) Mode.AGENT else Mode.CHAIN

    fun bodyOf(steps: List<String>): String =
        if (modeOf(steps) == Mode.AGENT) steps.first().removePrefix(AGENT_PREFIX) else steps.joinToString("\n")

    /** Готовые шаги для цепочки: нажал — добавилось строкой. */
    val TEMPLATES = listOf(
        "включи вайфай", "выключи вайфай", "включи блютуз", "выключи блютуз",
        "включи фонарик", "выключи фонарик", "громкость на 50", "яркость на 30",
        "включи не беспокоить", "включи музыку", "пауза", "открой телеграм",
        "поставь будильник на 7:00", "поставь таймер на 10 минут", "скажи погоду", "какой курс доллара",
    )
}
