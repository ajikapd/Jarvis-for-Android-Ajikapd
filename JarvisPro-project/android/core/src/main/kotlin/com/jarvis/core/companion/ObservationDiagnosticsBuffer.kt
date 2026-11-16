package com.jarvis.core.companion

import java.util.ArrayDeque

/**
 * Отдельный слой диагностики: кольцевой буфер недавних принятых observations.
 *
 * Это НЕ memory store и НЕ часть [PhoneContextBus] как «история истины».
 * Только для Settings → Companion UI / отладки. Будущая Memory — отдельный модуль.
 */
object ObservationDiagnosticsBuffer {

    private const val DEFAULT_LIMIT = 40

    private val lock = Any()
    private val deque = ArrayDeque<PhoneObservation>(DEFAULT_LIMIT)
    private var capacity = DEFAULT_LIMIT

    fun record(observation: PhoneObservation) {
        synchronized(lock) {
            if (deque.size >= capacity) deque.removeFirst()
            deque.addLast(observation)
        }
    }

    fun recent(limit: Int = 20): List<PhoneObservation> = synchronized(lock) {
        deque.toList().takeLast(limit.coerceAtLeast(0))
    }

    fun clear() {
        synchronized(lock) { deque.clear() }
    }

    fun resetForTests(limit: Int = DEFAULT_LIMIT) {
        synchronized(lock) {
            capacity = limit.coerceAtLeast(1)
            deque.clear()
        }
    }
}
