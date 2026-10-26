package com.jarvis.core.behavior

import com.jarvis.core.companion.PhoneObservation

/**
 * Bounded окно наблюдений для learning — не весь SQLite / не весь history.
 */
data class ObservationWindow(
    val observations: List<PhoneObservation>,
    val fromMillis: Long,
    val toMillis: Long,
) {
    val size: Int get() = observations.size

    companion object {
        fun from(
            recent: List<PhoneObservation>,
            nowMillis: Long = System.currentTimeMillis(),
            maxCount: Int = 200,
            maxAgeMs: Long = 14L * 24 * 3_600_000L, // 14 days
        ): ObservationWindow {
            val from = nowMillis - maxAgeMs
            val filtered = recent
                .filter { it.timestampMillis in from..nowMillis }
                .sortedBy { it.timestampMillis }
                .takeLast(maxCount.coerceAtLeast(0))
            return ObservationWindow(
                observations = filtered,
                fromMillis = filtered.firstOrNull()?.timestampMillis ?: from,
                toMillis = filtered.lastOrNull()?.timestampMillis ?: nowMillis,
            )
        }
    }
}
