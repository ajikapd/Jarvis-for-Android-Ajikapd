package com.jarvis.core.llm

/**
 * V2-04: model-aware output limits without blind hard truncate.
 */
object LlmOutputPolicy {

    fun maxChars(budget: LlmContextBudget): Int = when (budget) {
        LlmContextBudget.FAST -> 280
        LlmContextBudget.BALANCED -> 480
        LlmContextBudget.QUALITY -> 720
        LlmContextBudget.REASONING -> 960
    }

    fun apply(raw: String, budget: LlmContextBudget): String {
        var t = raw.trim()
        if (t.isBlank()) return t
        t = t.replace(Regex("""(?i)^(RESULT|TOOL|STATUS)\s*=\s*\S+.*"""), "").trim()
        t = t.replace(Regex("""(?i)^ANSWER\s*[:|\-–]\s*"""), "")
        val limit = maxChars(budget)
        if (t.length <= limit) return t
        return sentenceAwareTruncate(t, limit)
    }

    internal fun sentenceAwareTruncate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val parts = text.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return text.take(limit).trimEnd() + "…"
        val complete = parts.filter { it.lastOrNull() in setOf('.', '!', '?', '…') }
        var out = when {
            complete.isNotEmpty() -> complete.take(3).joinToString(" ")
            else -> parts.take(2).joinToString(" ")
        }
        if (out.length > limit) {
            out = out.take(limit - 1).trimEnd(',', ';', ':', ' ')
            if (!out.endsWith('…')) out += "…"
        }
        return out
    }
}
