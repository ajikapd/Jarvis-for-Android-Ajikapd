package com.jarvis.core.common.model

import java.util.UUID

/**
 * Нормализованное входное сообщение пользователя.
 * Источник (голос/текст) приходит из слоя захвата; на вертикальном срезе — текст.
 */
data class UserRequest(
    val text: String,
    val source: RequestSource = RequestSource.TEXT,
    val requestId: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val language: String = "ru"
)

enum class RequestSource {
    VOICE,
    TEXT,
    /** Действие из одобренной automation / trusted routine. */
    AUTOMATION,
    /** Решение без голосового запроса (Companion proactive). */
    PROACTIVE,
    /** Сырое системное событие (пока только diagnostics). */
    SYSTEM_EVENT,
    API,
    UNKNOWN,
}