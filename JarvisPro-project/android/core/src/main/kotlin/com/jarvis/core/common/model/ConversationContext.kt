package com.jarvis.core.common.model

/**
 * Отложенное действие, ждущее голос пользователя («да»/«нет» или выбор контакта).
 */
data class PendingAction(
    val toolCall: ToolCall,
    val prompt: String,
    val phase: Phase = Phase.CONFIRM,
    /** Варианты при неоднозначном контакте (имя → номер). */
    val choices: List<Choice> = emptyList(),
    /** V2-01: integrity fingerprint from ExecutionOrchestrator NeedsConfirmation. */
    val integrityFingerprint: String? = null,
) {
    enum class Phase {
        CONFIRM,
        DISAMBIGUATE,
        /** Ждём имя чата/контакта («Кому написать?»). */
        COLLECT_CHAT,
        /** Ждём текст сообщения («Что написать?»). */
        COLLECT_TEXT,
    }

    data class Choice(val label: String, val value: String)
}

/**
 * Краткосрочный контекст диалога. Ограничен по размеру.
 */
class ConversationContext(private val maxMessages: Int = 20) {

    private val inMemory = ArrayDeque<String>(maxMessages)

    @Volatile
    var pendingAction: PendingAction? = null

    /** Последний успешный вызов инструмента — для «ещё раз» / «то же самое, но…». */
    @Volatile
    var lastToolCall: ToolCall? = null

    val messages: List<String> get() = inMemory.toList()

    fun addUser(text: String) {
        add("user: $text")
    }

    fun addAssistant(text: String) {
        add("assistant: $text")
    }

    val lastUserRequest: String?
        get() = inMemory.lastOrNull { it.startsWith("user:") }

    fun clearPending() {
        pendingAction = null
    }

    fun clear() {
        inMemory.clear()
        pendingAction = null
        lastToolCall = null
    }

    private fun add(line: String) {
        if (inMemory.size >= maxMessages) inMemory.removeFirst()
        inMemory.addLast(line)
    }
}
