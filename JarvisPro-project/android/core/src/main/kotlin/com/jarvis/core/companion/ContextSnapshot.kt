package com.jarvis.core.companion

/**
 * Консистентный снимок текущего состояния телефона.
 * Не история и не memory — только point-in-time view поверх [PhoneContext].
 */
data class ContextSnapshot(
    val context: PhoneContext,
    val takenAtMillis: Long,
    val lastAcceptedObservationId: String? = null,
    val lastAcceptedAtMillis: Long = 0L,
    val lastAcceptedSource: ObservationSource? = null,
    val lastAcceptedType: ObservationType? = null,
) {
    fun describe(): String = context.describe()
}
