package com.jarvis.core.memory

/**
 * Политика disposition для MemoryCandidate.
 * Stage 5: консервативно — ROUTINE_HINT temporary, низкая уверенность reject,
 * USER_STATED может store, автоматический store для OBSERVED — нет (кроме явных исключений).
 */
class MemoryPolicy(
    private val minStoreConfidence: Float = 0.9f,
    private val minTemporaryConfidence: Float = 0.55f,
) {
    fun decide(candidate: MemoryCandidate): MemoryPolicyDecision {
        if (candidate.confidence < minTemporaryConfidence) {
            return MemoryPolicyDecision(
                MemoryDisposition.REJECT,
                "low_confidence",
                candidate,
            )
        }
        when (candidate.provenance) {
            MemoryProvenance.USER_STATED -> {
                val disp = if (candidate.confidence >= minStoreConfidence) {
                    MemoryDisposition.STORE
                } else {
                    MemoryDisposition.TEMPORARY
                }
                return MemoryPolicyDecision(disp, "user_stated", candidate)
            }
            MemoryProvenance.INFERRED ->
                return MemoryPolicyDecision(
                    MemoryDisposition.REJECT,
                    "inferred_disabled_stage5",
                    candidate,
                )
            MemoryProvenance.OBSERVED -> Unit
        }
        // OBSERVED: не store автоматически
        return when (candidate.kind) {
            MemoryKind.ROUTINE -> MemoryPolicyDecision(
                MemoryDisposition.TEMPORARY,
                "routine_hint_observed",
                candidate,
            )
            MemoryKind.EPISODIC -> MemoryPolicyDecision(
                MemoryDisposition.TEMPORARY,
                "episodic_observed",
                candidate,
            )
            MemoryKind.PREFERENCE, MemoryKind.SEMANTIC, MemoryKind.PROCEDURAL, MemoryKind.WORKING ->
                MemoryPolicyDecision(
                    MemoryDisposition.TEMPORARY,
                    "observed_temporary_only",
                    candidate,
                )
        }
    }
}
