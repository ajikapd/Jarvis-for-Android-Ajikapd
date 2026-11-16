package com.jarvis.core.companion

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Тонкая шина perception → state (Companion Stage 3.5).
 *
 * - Наблюдатели публикуют [PhoneObservation]
 * - [ContextAggregator] решает accept/drop и обновляет [PhoneContext]
 * - [snapshot] даёт консистентный [ContextSnapshot]
 * - Не хранит историю: recent diagnostics → [ObservationDiagnosticsBuffer]
 *
 * Без LLM, autonomy, learning, memory store.
 */
object PhoneContextBus {

    private val lock = Any()
    private val aggregator = ContextAggregator()

    private val _observations = MutableSharedFlow<PhoneObservation>(extraBufferCapacity = 128)
    val observations: SharedFlow<PhoneObservation> = _observations.asSharedFlow()

    private val _context = MutableStateFlow(PhoneContext())
    val context: StateFlow<PhoneContext> = _context.asStateFlow()

    @Volatile
    private var lastAcceptedId: String? = null

    @Volatile
    private var lastAcceptedAtMillis: Long = 0L

    @Volatile
    private var lastAcceptedSource: ObservationSource? = null

    @Volatile
    private var lastAcceptedType: ObservationType? = null

    /**
     * Публикует observation. Возвращает true, если aggregator принял событие.
     */
    fun publish(observation: PhoneObservation): Boolean {
        synchronized(lock) {
            val result = aggregator.aggregate(_context.value, observation)
            if (!result.accepted) return false
            _context.value = result.context
            lastAcceptedId = observation.id
            lastAcceptedAtMillis = observation.timestampMillis
            lastAcceptedSource = observation.source
            lastAcceptedType = observation.type
            ObservationDiagnosticsBuffer.record(observation)
            _observations.tryEmit(observation)
            return true
        }
    }

    /** Консистентный снимок текущего состояния. */
    fun snapshot(): ContextSnapshot = synchronized(lock) {
        ContextSnapshot(
            context = _context.value,
            takenAtMillis = System.currentTimeMillis(),
            lastAcceptedObservationId = lastAcceptedId,
            lastAcceptedAtMillis = lastAcceptedAtMillis,
            lastAcceptedSource = lastAcceptedSource,
            lastAcceptedType = lastAcceptedType,
        )
    }

    /** Только текущий PhoneContext (без meta snapshot). */
    fun current(): PhoneContext = _context.value

    /**
     * Diagnostics-only recent buffer (не memory).
     * Предпочтительно [ObservationDiagnosticsBuffer.recent].
     */
    fun recent(limit: Int = 20): List<PhoneObservation> =
        ObservationDiagnosticsBuffer.recent(limit)

    fun resetForTests() {
        synchronized(lock) {
            aggregator.reset()
            _context.value = PhoneContext()
            lastAcceptedId = null
            lastAcceptedAtMillis = 0L
            lastAcceptedSource = null
            lastAcceptedType = null
            ObservationDiagnosticsBuffer.resetForTests()
        }
    }
}
