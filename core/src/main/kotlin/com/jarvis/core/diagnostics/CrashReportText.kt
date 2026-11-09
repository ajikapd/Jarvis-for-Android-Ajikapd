package com.jarvis.core.diagnostics

/**
 * Текст отчёта об ошибке, который пользователь сам отправляет разработчику.
 * Всё личное прячется: сказанные команды, ответы, ключи, телефоны, почта.
 */
object CrashReportText {
    private val QUOTED = Regex("(\"|«)([^\"»]{2,})(\"|»)")
    private val KEY = Regex("\\b(gsk_|AIza|sk-)[A-Za-z0-9_\\-]{4,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("\\+?\\d[\\d\\s()-]{8,}\\d")
    private val SPOKEN = Regex("(?i)\\b(req|reply|text|query|command|asr|final|partial|heard|said|track|title)=\\S.*")

    /** Строка журнала без личных данных. */
    fun scrub(line: String): String {
        var s = SPOKEN.replace(line) { m -> m.value.substringBefore('=') + "=<скрыто>" }
        s = KEY.replace(s) { m -> m.groupValues[1] + "<ключ>" }
        s = EMAIL.replace(s, "<почта>")
        s = PHONE.replace(s, "<номер>")
        s = QUOTED.replace(s) { m -> m.groupValues[1] + "…" + m.groupValues[3] }
        return s
    }

    /**
     * Отчёт: шапка «ключ: значение», затем разделы. Слишком длинный текст обрезается,
     * чтобы его можно было вставить в мессенджер.
     */
    fun build(
        meta: List<Pair<String, String>>,
        sections: List<Pair<String, String>>,
        maxChars: Int = 60_000,
    ): String {
        val out = StringBuilder("Отчёт Jarvis об ошибке\n")
        meta.forEach { (k, v) -> out.append(k).append(": ").append(v).append('\n') }
        sections.forEach { (title, body) ->
            if (body.isBlank()) return@forEach
            out.append("\n== ").append(title).append(" ==\n")
            body.lineSequence().forEach { out.append(scrub(it)).append('\n') }
        }
        return if (out.length <= maxChars) out.toString() else out.substring(0, maxChars) + "\n…обрезано"
    }
}
