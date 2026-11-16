package com.jarvis.core.llm

object ConversationHistorySelector {

    private val followUpMarkers = listOf(
        "первый", "второй", "третий", "четверт", "последн", "вариант",
        "сравни", "дешевле", "дороже", "их", "оба", "обе", "этот", "тот",
        "а теперь", "а под", "ещё раз", "еще раз", "продолж", "подробнее",
    )

    fun select(
        lines: List<String>,
        userText: String,
        budget: LlmContextBudget,
    ): List<String> {
        val turns = lines.filter {
            it.startsWith("user:", ignoreCase = true) ||
                it.startsWith("assistant:", ignoreCase = true)
        }
        if (turns.isEmpty()) return emptyList()
        val maxTurns = when (budget) {
            LlmContextBudget.FAST -> 4
            LlmContextBudget.BALANCED -> 8
            LlmContextBudget.QUALITY -> 12
            LlmContextBudget.REASONING -> 16
        }
        val recent = turns.takeLast(maxTurns).toMutableList()
        if (looksLikeReferenceFollowUp(userText) && turns.size > recent.size) {
            val older = turns.dropLast(recent.size).takeLast(2)
            recent.addAll(0, older)
        }
        return recent.distinct().take(maxTurns + 2)
    }

    fun referenceHint(userText: String, lastAssistant: String, prevUser: String): String? {
        if (!looksLikeReferenceFollowUp(userText)) return null
        val parts = buildList {
            if (prevUser.isNotBlank()) add("предыдущий запрос: «${prevUser.take(120)}»")
            if (lastAssistant.isNotBlank()) add("предыдущий ответ: «${lastAssistant.take(280)}»")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun looksLikeReferenceFollowUp(userText: String): Boolean {
        val t = userText.lowercase()
        if (t.length > 120) return false
        return followUpMarkers.any { t.contains(it) } ||
            t.startsWith("а ") || t.startsWith("ну ")
    }
}
