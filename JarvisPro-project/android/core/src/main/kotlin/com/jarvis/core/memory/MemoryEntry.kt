package com.jarvis.core.memory

import com.jarvis.core.companion.Sensitivity

/**
 * Lifecycle durable memory (Stage 5.5).
 * Не путать с [MemoryDisposition] кандидата.
 */
enum class MemoryStatus {
    ACTIVE,
    UPDATED,
    STALE,
    EXPIRED,
    ARCHIVED,
    DELETED,
}

/**
 * Durable memory entry — только после [MemoryPolicy] → STORE/TEMPORARY.
 * Не observation и не PhoneContext.
 */
data class MemoryEntry(
    val id: String,
    val type: MemoryKind,
    val content: String,
    val provenance: MemoryProvenance,
    val confidence: Float,
    val sensitivity: Sensitivity,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val firstObservedAtMillis: Long,
    val lastObservedAtMillis: Long,
    /** null = без срока (обычно USER_STATED PREFERENCE). */
    val expiresAtMillis: Long?,
    val evidenceCount: Int,
    val evidenceObservationIds: List<String> = emptyList(),
    val packageName: String? = null,
    val entities: Map<String, String> = emptyMap(),
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    /** Исходный disposition (TEMPORARY vs STORE). */
    val disposition: MemoryDisposition,
) {
    fun isAlive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (status == MemoryStatus.DELETED || status == MemoryStatus.EXPIRED ||
            status == MemoryStatus.ARCHIVED
        ) {
            return false
        }
        val exp = expiresAtMillis ?: return true
        return nowMillis < exp
    }

    fun explain(): String = buildString {
        append(content)
        append(" [").append(provenance.name).append("/").append(type.name).append("]")
        append(" conf=").append(String.format(java.util.Locale.US, "%.2f", confidence))
        append(" sens=").append(sensitivity.name)
        append(" status=").append(status.name)
        append(" evidence=").append(evidenceCount)
    }
}
