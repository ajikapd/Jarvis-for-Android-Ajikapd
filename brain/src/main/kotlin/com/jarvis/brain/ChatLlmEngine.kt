package com.jarvis.brain

/**
 * Локальная chat-LLM для свободного диалога (отдельно от FunctionGemma tool-calling).
 */
interface ChatLlmEngine {
    /** Файл модели на диске есть. */
    fun isReady(): Boolean

    /** Модель уже в RAM (прогрета). */
    fun isLoaded(): Boolean

    /** Блокирующая загрузка в RAM. Вызывать с background-потока. */
    fun warm()

    /** Блокирующая генерация; null = ошибка / таймаут. */
    fun generate(
        userText: String,
        history: List<String>,
        memories: String = "",
        persona: String = "CLASSIC",
        assembled: com.jarvis.core.llm.AssembledLlmContext? = null,
    ): String?

    fun generateWithContext(
        userText: String,
        persona: String,
        assembled: com.jarvis.core.llm.AssembledLlmContext,
    ): String? = generate(
        userText,
        assembled.historyLines,
        assembled.legacyMemoriesSnippet,
        persona,
        assembled,
    )

    /**
     * Structured decision для semantic brain.
     * По умолчанию: [generate] + [LlmDecisionParser].
     */
    fun decide(
        userText: String,
        history: List<String>,
        memories: String = "",
        persona: String = "CLASSIC",
    ): LlmStructuredDecision? {
        val text = generate(userText, history, memories, persona) ?: return null
        return LlmDecisionParser.parse(text)
            ?: LlmStructuredDecision(LlmDecisionKind.ANSWER, text.take(400), raw = text)
    }
}
