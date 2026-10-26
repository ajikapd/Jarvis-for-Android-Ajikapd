package com.jarvis.brain

/**
 * Машинно-читаемое решение semantic brain (Этап A).
 * Свободный текст — в [utterance] для TTS; исполнение — только через Policy → Capability позже.
 */
enum class LlmDecisionKind {
    ANSWER,
    QUESTION,
    CLARIFICATION,
    ACTION,
    PLAN,
    CONFIRMATION_REQUIRED,
    REFUSAL,
    ERROR,
    FOLLOW_UP,
    UNKNOWN,
}

data class LlmStructuredDecision(
    val kind: LlmDecisionKind,
    val utterance: String,
    val confidence: Float = 0.6f,
    val goal: String? = null,
    val capabilityHint: String? = null,
    val raw: String = "",
)

object LlmDecisionParser {

    private val KIND_LINE = Regex(
        """^\s*(ANSWER|QUESTION|CLARIFICATION|ACTION|PLAN|CONFIRMATION_REQUIRED|REFUSAL|ERROR|FOLLOW_UP)\s*[:|\-–]\s*(.+)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val KIND_TAG = Regex(
        """(?i)\b(ANSWER|QUESTION|CLARIFICATION|ACTION|PLAN|CONFIRMATION_REQUIRED|REFUSAL|ERROR|FOLLOW_UP)\b""",
    )

    fun parse(raw: String): LlmStructuredDecision? {
        val t = raw.trim()
        if (t.isBlank()) return null

        val lineMatch = KIND_LINE.find(t)
        if (lineMatch != null) {
            val kind = kindOf(lineMatch.groupValues[1])
            val utter = lineMatch.groupValues[2].trim().take(400)
            if (kind != null && utter.isNotBlank()) {
                return LlmStructuredDecision(kind, utter, confidence = 0.7f, raw = t)
            }
        }

        val kindJson = Regex(""""kind"\s*:\s*"([A-Za-z_]+)"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.get(1)
        val utterJson = Regex(""""utterance"\s*:\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
        if (kindJson != null && !utterJson.isNullOrBlank()) {
            val kind = kindOf(kindJson) ?: LlmDecisionKind.ANSWER
            return LlmStructuredDecision(kind, utterJson.take(400), confidence = 0.75f, raw = t)
        }

        val cleaned = t.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("{") }
            .joinToString(" ")
            .trim()
        if (cleaned.isBlank()) return null
        val tagged = KIND_TAG.find(cleaned)?.groupValues?.get(1)
        val kind = kindOf(tagged) ?: LlmDecisionKind.ANSWER
        val utter = cleaned
            .replace(
                Regex(
                    """^(ANSWER|QUESTION|CLARIFICATION|ACTION|PLAN|CONFIRMATION_REQUIRED|REFUSAL|ERROR|FOLLOW_UP)\s*[:|\-–]\s*""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()
            .ifBlank { cleaned }
        return LlmStructuredDecision(kind, utter.take(400), confidence = 0.55f, raw = t)
    }

    private fun kindOf(name: String?): LlmDecisionKind? {
        if (name.isNullOrBlank()) return null
        return runCatching { LlmDecisionKind.valueOf(name.uppercase()) }.getOrNull()
    }
}
