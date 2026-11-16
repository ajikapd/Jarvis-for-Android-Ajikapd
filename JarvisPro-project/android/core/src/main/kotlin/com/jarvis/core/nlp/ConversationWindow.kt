package com.jarvis.core.nlp

/** Когда слушать дальше без слова «Джарвис». */
object ConversationWindow {
    const val CONVERSATION_MS = 7_000L
    const val CONFIRMATION_MS = 10_000L

    /** Разговор продолжается после вопроса Джарвиса и после ответа модели; команда разговор закрывает. */
    fun keepListening(reply: String, isChat: Boolean, enabled: Boolean): Boolean =
        enabled && (isChat || reply.trimEnd().endsWith("?"))
}
