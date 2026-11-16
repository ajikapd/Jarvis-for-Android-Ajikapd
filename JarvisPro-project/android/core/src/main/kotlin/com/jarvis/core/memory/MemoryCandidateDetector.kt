package com.jarvis.core.memory

import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity

/**
 * Детектор кандидатов в память из текущего снимка + недавних observations.
 * Не пишет в memory store — только предлагает [MemoryCandidate].
 */
class MemoryCandidateDetector(
    private val routineMinEvidence: Int = 3,
    private val routineWindowMs: Long = 3 * 60 * 60 * 1000L, // 3h
) {

    fun detect(
        snapshot: ContextSnapshot,
        recent: List<PhoneObservation>,
        nowMillis: Long = snapshot.takenAtMillis,
    ): List<MemoryCandidate> {
        val out = mutableListOf<MemoryCandidate>()
        detectRoutineHint(snapshot, recent, nowMillis)?.let { out += it }
        detectEpisodicAppSwitch(snapshot, recent, nowMillis)?.let { out += it }
        return out
    }

    private fun detectRoutineHint(
        snapshot: ContextSnapshot,
        recent: List<PhoneObservation>,
        nowMillis: Long,
    ): MemoryCandidate? {
        val pkg = snapshot.context.currentApp ?: return null
        val fg = recent.filter {
            it.type == ObservationType.APP_FOREGROUND &&
                it.packageName == pkg &&
                nowMillis - it.timestampMillis <= routineWindowMs
        }
        if (fg.size < routineMinEvidence) return null
        val first = fg.minOf { it.timestampMillis }
        val last = fg.maxOf { it.timestampMillis }
        val conf = (0.45f + 0.08f * fg.size).coerceAtMost(0.85f)
        return MemoryCandidate(
            kind = MemoryKind.ROUTINE,
            provenance = MemoryProvenance.OBSERVED,
            statement = "Пользователь часто открывает $pkg (наблюдение)",
            evidenceCount = fg.size,
            confidence = conf,
            firstSeenMillis = first,
            lastSeenMillis = last,
            sensitivity = Sensitivity.PRIVATE,
            packageName = pkg,
            evidenceObservationIds = fg.map { it.id }.takeLast(12),
            entities = mapOf(
                "type" to "ROUTINE_HINT",
                "package" to pkg,
            ),
        )
    }

    private fun detectEpisodicAppSwitch(
        snapshot: ContextSnapshot,
        recent: List<PhoneObservation>,
        nowMillis: Long,
    ): MemoryCandidate? {
        val cur = snapshot.context.currentApp ?: return null
        val prev = snapshot.context.previousApp ?: return null
        if (cur == prev) return null
        val lastFg = recent.lastOrNull {
            it.type == ObservationType.APP_FOREGROUND && it.packageName == cur
        } ?: return null
        if (nowMillis - lastFg.timestampMillis > 60_000L) return null
        return MemoryCandidate(
            kind = MemoryKind.EPISODIC,
            provenance = MemoryProvenance.OBSERVED,
            statement = "Переключение $prev → $cur",
            evidenceCount = 1,
            confidence = lastFg.confidence.coerceAtMost(0.7f),
            firstSeenMillis = lastFg.timestampMillis,
            lastSeenMillis = lastFg.timestampMillis,
            sensitivity = Sensitivity.NORMAL,
            packageName = cur,
            evidenceObservationIds = listOf(lastFg.id),
            entities = mapOf(
                "type" to "APP_SWITCH",
                "from" to prev,
                "to" to cur,
            ),
        )
    }
}
