package com.jarvis.core.memory

import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneObservation

/**
 * Stage 5 pipeline: snapshot + recent → candidates → sensitivity → policy.
 *
 * Не смешивается с PhoneContextBus / ObservationDiagnosticsBuffer.
 * Не BehaviorLearning / не autonomy.
 * Durable store = Stage 5.5; здесь только disposition decisions.
 */
class MemoryCandidatePipeline(
    private val detector: MemoryCandidateDetector = MemoryCandidateDetector(),
    private val sensitivityFilter: SensitivityFilter = SensitivityFilter(),
    private val policy: MemoryPolicy = MemoryPolicy(),
) {
    fun evaluate(
        snapshot: ContextSnapshot,
        recent: List<PhoneObservation>,
    ): List<MemoryPolicyDecision> {
        return detector.detect(snapshot, recent).map { c ->
            sensitivityFilter.filter(c) ?: policy.decide(c)
        }
    }

    fun resetForTests() = Unit
}
