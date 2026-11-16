package com.jarvis.core.llm

/**
 * Локальная модель не должна получить промпт длиннее своего контекста: llama.cpp в этом
 * случае не возвращает ошибку, а аварийно завершает процесс. Токенизатора снаружи нет,
 * поэтому считаем с запасом: русский текст у Qwen — не меньше ~2.2 символа на токен.
 */
object PromptFit {
    const val CHARS_PER_TOKEN = 2.2
    /** Служебные токены шаблона чата (роли, разделители). */
    const val TEMPLATE_TOKENS = 32

    data class Fitted(val system: String, val prompt: String)

    fun maxChars(contextSize: Int, maxTokens: Int): Int =
        ((contextSize - maxTokens - TEMPLATE_TOKENS).coerceAtLeast(16) * CHARS_PER_TOKEN).toInt()

    /** Вопрос важнее системной части: сначала урезается система, потом начало промпта. */
    fun fit(system: String, prompt: String, contextSize: Int, maxTokens: Int): Fitted {
        val limit = maxChars(contextSize, maxTokens)
        if (system.length + prompt.length <= limit) return Fitted(system, prompt)
        val p = if (prompt.length > limit) prompt.takeLast(limit) else prompt
        val s = system.take((limit - p.length).coerceAtLeast(0))
        return Fitted(s, p)
    }
}
