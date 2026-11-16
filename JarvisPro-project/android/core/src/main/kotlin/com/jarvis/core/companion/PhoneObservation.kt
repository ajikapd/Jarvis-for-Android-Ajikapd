package com.jarvis.core.companion

import java.util.UUID

/**
 * Immutable факт события телефона (perception).
 * Не память, не ToolCall, не мутабельный state.
 *
 * Provenance = [source]; уверенность = [confidence]; время = [timestampMillis].
 */
data class PhoneObservation(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Provenance / источник наблюдения. */
    val source: ObservationSource,
    val type: ObservationType,
    val packageName: String? = null,
    val screenId: String? = null,
    val summary: String? = null,
    val entities: Map<String, String> = emptyMap(),
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val confidence: Float = 1f,
) {
    /** Явный alias provenance для читаемости API. */
    val provenance: ObservationSource get() = source
}
