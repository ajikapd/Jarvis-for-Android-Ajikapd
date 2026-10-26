package com.jarvis.core.memory

import com.jarvis.core.companion.Sensitivity

/**
 * Retention / TTL — решает Policy-слой, не Repository.
 * Разные типы/источники/чувствительность → разный срок.
 */
object MemoryRetention {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /**
     * @return epoch millis истечения, или null если бессрочно (до явного delete).
     */
    fun expiresAt(
        nowMillis: Long,
        kind: MemoryKind,
        provenance: MemoryProvenance,
        sensitivity: Sensitivity,
        confidence: Float,
        disposition: MemoryDisposition,
    ): Long? {
        if (sensitivity == Sensitivity.SECRET) return nowMillis // не должно сохраняться
        if (disposition == MemoryDisposition.REJECT) return nowMillis

        if (disposition == MemoryDisposition.TEMPORARY) {
            val ttl = when (kind) {
                MemoryKind.WORKING -> 2 * HOUR
                MemoryKind.EPISODIC -> 2 * DAY
                MemoryKind.ROUTINE -> 7 * DAY
                MemoryKind.PREFERENCE -> 3 * DAY
                MemoryKind.SEMANTIC, MemoryKind.PROCEDURAL -> 5 * DAY
            }
            return nowMillis + ttl
        }

        // STORE
        return when {
            provenance == MemoryProvenance.USER_STATED && kind == MemoryKind.PREFERENCE -> null
            provenance == MemoryProvenance.USER_STATED && confidence >= 0.95f -> null
            kind == MemoryKind.PREFERENCE -> nowMillis + 180 * DAY
            kind == MemoryKind.ROUTINE -> nowMillis + 60 * DAY
            kind == MemoryKind.EPISODIC -> nowMillis + 30 * DAY
            kind == MemoryKind.SEMANTIC -> nowMillis + 90 * DAY
            kind == MemoryKind.PROCEDURAL -> nowMillis + 90 * DAY
            kind == MemoryKind.WORKING -> nowMillis + 12 * HOUR
            else -> nowMillis + 14 * DAY
        }
    }

    /** Когда ACTIVE → STALE без явного обновления. */
    fun staleAfterMillis(kind: MemoryKind, provenance: MemoryProvenance): Long = when {
        provenance == MemoryProvenance.USER_STATED -> 180 * DAY
        kind == MemoryKind.ROUTINE -> 21 * DAY
        kind == MemoryKind.EPISODIC -> 14 * DAY
        kind == MemoryKind.WORKING -> 6 * HOUR
        else -> 30 * DAY
    }
}
