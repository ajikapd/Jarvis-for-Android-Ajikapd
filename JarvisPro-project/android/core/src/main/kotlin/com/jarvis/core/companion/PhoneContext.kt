package com.jarvis.core.companion

/**
 * Текущее состояние телефона (не memory, не history).
 * Обновляется только через [ContextAggregator] / [PhoneContextBus].
 */
data class PhoneContext(
    val currentApp: String? = null,
    val previousApp: String? = null,
    /** Provenance последнего принятого FG (Accessibility / UsageStats / …). */
    val currentAppSource: ObservationSource? = null,
    val currentAppConfidence: Float? = null,
    val currentAppAtMillis: Long = 0L,
    val screenOn: Boolean = true,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val headphonesConnected: Boolean = false,
    val networkType: String? = null,
    val lastNotificationPackage: String? = null,
    val lastNotificationSummary: String? = null,
    val lastObservationSummary: String? = null,
    val lastObservationAtMillis: Long = 0L,
    val observationCount: Long = 0L,
) {
    fun describe(): String = buildString {
        append("app=").append(currentApp ?: "—")
        if (!previousApp.isNullOrBlank()) append(" (←").append(previousApp).append(")")
        currentAppSource?.let { append(" via=").append(it.name.take(6)) }
        currentAppConfidence?.let {
            append("@").append(String.format(java.util.Locale.US, "%.1f", it))
        }
        append(" · screen=").append(if (screenOn) "on" else "off")
        batteryPercent?.let { append(" · battery=").append(it).append("%") }
        if (charging) append(" charging")
        if (headphonesConnected) append(" · headphones")
        networkType?.let { append(" · net=").append(it) }
        lastNotificationPackage?.let { append(" · notif=").append(it) }
        append(" · events=").append(observationCount)
    }
}
