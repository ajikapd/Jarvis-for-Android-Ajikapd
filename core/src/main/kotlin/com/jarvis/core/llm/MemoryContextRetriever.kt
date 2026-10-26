package com.jarvis.core.llm

import com.jarvis.core.skills.SkillRetriever

/**
 * V2-04: deterministic memory relevance (no LLM retrieval).
 */
object MemoryContextRetriever {

    private val memoryQuestionMarkers = listOf(
        "помнишь", "помню", "память", "обычно", "предпочита", "зовут", "имя",
        "vpn", "настрой", "люблю", "ненавиж", "всегда", "никогда",
    )

    fun needsMemory(query: String): Boolean {
        val q = query.lowercase()
        return memoryQuestionMarkers.any { q.contains(it) }
    }

    fun rank(
        query: String,
        snippets: List<MemorySnippet>,
        limit: Int,
        cloudAllowed: Boolean,
    ): List<MemorySnippet> {
        if (snippets.isEmpty()) return emptyList()
        val qWords = SkillRetriever.defaultNormalize(query)
        val needs = needsMemory(query)
        return snippets
            .filter { cloudAllowed || !isCloudBlocked(it) }
            .map { s ->
                val words = SkillRetriever.defaultNormalize(s.text)
                var score = s.score
                if (qWords.isNotEmpty() && words.isNotEmpty()) {
                    val hits = qWords.count { it in words.toSet() }
                    score += hits.toFloat() / qWords.size
                }
                if (needs && s.source.contains("companion", ignoreCase = true)) score += 0.2f
                s.copy(score = score)
            }
            .filter { needs || it.score > 0.15f }
            .sortedByDescending { it.score }
            .take(limit.coerceIn(0, 8))
    }

    private fun isCloudBlocked(s: MemorySnippet): Boolean {
        val sens = s.sensitivity.uppercase()
        return sens == "PRIVATE" || sens == "SECRET" || sens == "LOCAL_ONLY"
    }

    fun formatBlock(snippets: List<MemorySnippet>, maxChars: Int): String {
        if (snippets.isEmpty()) return ""
        return snippets.joinToString("\n") { "- ${it.text.take(160)} [${it.source}]" }
            .take(maxChars)
    }
}
