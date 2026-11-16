package com.jarvis.core.llm

object CapabilityContextSelector {

    private val capabilityMarkers = listOf(
        "открой", "запусти", "включи", "выключи", "найди", "браузер",
        "telegram", "фонарик", "громк", "погод", "таймер", "позвони",
        "отправ", "сделай", "помоги", "план", "шаг",
    )

    fun needsCapabilities(query: String, kind: LlmContextKind): Boolean {
        if (kind == LlmContextKind.PLAN) return true
        val q = query.lowercase()
        return capabilityMarkers.any { q.contains(it) }
    }

    fun formatLines(lines: List<String>, maxLines: Int, maxChars: Int): List<String> =
        lines.take(maxLines).map { it.take(120) }.filter { it.isNotBlank() }
            .let { list ->
                var total = 0
                val out = mutableListOf<String>()
                for (l in list) {
                    if (total + l.length > maxChars) break
                    out += l
                    total += l.length
                }
                out
            }
}
