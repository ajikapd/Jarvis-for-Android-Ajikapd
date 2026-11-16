package com.jarvis.core.memory

import com.jarvis.core.companion.Sensitivity

/**
 * Фильтр чувствительности кандидатов.
 * SECRET всегда reject; PRIVATE → temporary по умолчанию (Stage 5).
 */
class SensitivityFilter {
    fun filter(candidate: MemoryCandidate): MemoryPolicyDecision? {
        return when (candidate.sensitivity) {
            Sensitivity.SECRET -> MemoryPolicyDecision(
                disposition = MemoryDisposition.REJECT,
                reason = "secret_blocked",
                candidate = candidate,
            )
            Sensitivity.PRIVATE -> null // policy решит temporary/store
            Sensitivity.NORMAL, Sensitivity.PUBLIC -> null
        }
    }
}
