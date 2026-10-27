package com.jarvis.core.storage

/**
 * Политика сроков и объёмов постоянной истории. Чистый Kotlin — проверяется
 * JVM-тестами, применяется репозиториями и загрузкой контекста.
 */
object RetentionPolicy {

    private const val HOUR_MS: Long = 60L * 60L * 1000L
    private const val DAY_MS: Long = 24L * HOUR_MS

    /** Записи разговора старше 90 дней удаляются (кроме помеченных keep=1). */
    const val CONVERSATION_TTL_MS: Long = 90L * DAY_MS

    /** Насколько свежим должен быть разговор, чтобы попасть в контекст после рестарта. */
    const val RECENT_WINDOW_MS: Long = 48L * HOUR_MS

    /** Сколько последних реплик поднимаем в ConversationContext (не больше его лимита). */
    const val CONTEXT_RECENT_LIMIT: Int = 20

    /** Сколько последних пузырей поднимаем в ConversationStore (UI). */
    const val STORE_RECENT_LIMIT: Int = 60

    /** Граница «старше этого — удалить» для [now]. */
    fun cutoff(nowMillis: Long, ttlMs: Long = CONVERSATION_TTL_MS): Long = nowMillis - ttlMs

    /** Пора ли удалять запись: срок вышел и она не отмечена keep. */
    fun isExpired(createdAtMillis: Long, nowMillis: Long, keep: Boolean, ttlMs: Long = CONVERSATION_TTL_MS): Boolean =
        !keep && createdAtMillis < cutoff(nowMillis, ttlMs)

    /** Попадает ли запись в окно «недавнего» разговора. */
    fun isRecent(createdAtMillis: Long, nowMillis: Long, windowMs: Long = RECENT_WINDOW_MS): Boolean {
        if (createdAtMillis > nowMillis) return true // часы устройства могли уйти назад
        return nowMillis - createdAtMillis <= windowMs
    }

    /** Факт с истёкшим expires_at больше не отдаём (NULL = бессрочный). */
    fun isFactExpired(expiresAtMillis: Long?, nowMillis: Long): Boolean =
        expiresAtMillis != null && expiresAtMillis <= nowMillis

    /** Возраст записи в миллисекундах (для метаданных DEBUG_MEMORY). */
    fun ageMs(createdAtMillis: Long, nowMillis: Long): Long =
        if (createdAtMillis <= 0L) -1L else (nowMillis - createdAtMillis).coerceAtLeast(0L)
}
