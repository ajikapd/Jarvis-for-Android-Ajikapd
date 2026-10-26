package com.jarvis.core.autonomy

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 8C/8D: лёгкий in-memory audit trail вердиктов.
 * Не пишет PII / raw screen / secrets. Bounded.
 */
object AutonomyAuditLog {
    private const val CAPACITY = 64
    private val entries = CopyOnWriteArrayList<AutonomyAuditEntry>()

    fun record(verdict: AutonomyVerdict, stage: String = "gate") {
        val actionId = verdict.actionRequest?.proposedAction?.actionId
        val entry = AutonomyAuditEntry(
            atMillis = System.currentTimeMillis(),
            stage = stage,
            verdictType = verdict.type,
            reasonCode = verdict.reasonCode,
            actionId = actionId,
            noteSummary = verdict.notes.take(4).joinToString(";"),
        )
        entries.add(entry)
        while (entries.size > CAPACITY) {
            entries.removeAt(0)
        }
    }

    fun recent(limit: Int = 16): List<AutonomyAuditEntry> =
        entries.takeLast(limit.coerceIn(1, CAPACITY))

    fun clearForTests() {
        entries.clear()
    }
}

data class AutonomyAuditEntry(
    val atMillis: Long,
    val stage: String,
    val verdictType: AutonomyVerdictType,
    val reasonCode: AutonomyReasonCode,
    val actionId: String?,
    val noteSummary: String,
)
