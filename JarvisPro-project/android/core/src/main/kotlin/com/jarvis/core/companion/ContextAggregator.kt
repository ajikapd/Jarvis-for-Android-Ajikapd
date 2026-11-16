package com.jarvis.core.companion

/**
 * Результат попытки применить [PhoneObservation] к текущему [PhoneContext].
 */
data class AggregateResult(
    val accepted: Boolean,
    val context: PhoneContext,
    val dropReason: String? = null,
)

/**
 * Чистая агрегация observation → текущий PhoneContext.
 *
 * Инварианты Stage 3.5 / 4.5:
 * - PhoneObservation — immutable факт события.
 * - PhoneContext — только текущее состояние, не история.
 * - APP_FOREGROUND: Accessibility приоритетнее UsageStats.
 * - WINDOW_CHANGED coalesce отдельно от FG (анти-storm Stage 4).
 */
class ContextAggregator(
    private val config: Config = Config(),
) {

    data class Config(
        val sameFgDebounceMs: Long = 1_500L,
        val windowCoalesceMs: Long = 2_500L,
        val batteryCoalesceMs: Long = 2_500L,
        val networkCoalesceMs: Long = 3_000L,
        val notificationDebounceMs: Long = 1_200L,
        /** UsageStats может сменить FG только если a11y-источник старше этого окна (backup). */
        val usageStatsStaleOverrideMs: Long = 45_000L,
    )

    private var lastFgPackage: String? = null
    private var lastFgAtMillis: Long = 0L
    private var lastWindowKey: String? = null
    private var lastWindowAtMillis: Long = 0L
    private var lastBatteryKey: String? = null
    private var lastBatteryAtMillis: Long = 0L
    private var lastNetworkKey: String? = null
    private var lastNetworkAtMillis: Long = 0L
    private var lastNotifKey: String? = null
    private var lastNotifAtMillis: Long = 0L

    fun reset() {
        lastFgPackage = null
        lastFgAtMillis = 0L
        lastWindowKey = null
        lastWindowAtMillis = 0L
        lastBatteryKey = null
        lastBatteryAtMillis = 0L
        lastNetworkKey = null
        lastNetworkAtMillis = 0L
        lastNotifKey = null
        lastNotifAtMillis = 0L
    }

    fun aggregate(prev: PhoneContext, o: PhoneObservation): AggregateResult {
        val drop = dropReason(prev, o)
        if (drop != null) {
            return AggregateResult(accepted = false, context = prev, dropReason = drop)
        }
        val next = apply(prev, o)
        rememberAccepted(o)
        return AggregateResult(accepted = true, context = next)
    }

    private fun dropReason(prev: PhoneContext, o: PhoneObservation): String? {
        when (o.type) {
            ObservationType.APP_FOREGROUND -> {
                val pkg = o.packageName?.takeIf { it.isNotBlank() } ?: return "empty_package"
                if (o.source == ObservationSource.USAGE_STATS) {
                    val a11yFresh = prev.currentAppSource == ObservationSource.ACCESSIBILITY &&
                        prev.currentAppAtMillis > 0L &&
                        (o.timestampMillis - prev.currentAppAtMillis) < config.usageStatsStaleOverrideMs
                    if (a11yFresh) {
                        return if (pkg == prev.currentApp) {
                            "usage_stats_dup_a11y"
                        } else {
                            "usage_stats_blocked_by_a11y"
                        }
                    }
                    if (pkg == prev.currentApp) return "usage_stats_same_app"
                }
                if (pkg == lastFgPackage &&
                    (o.timestampMillis - lastFgAtMillis) < config.sameFgDebounceMs
                ) {
                    return "fg_debounce"
                }
            }
            ObservationType.WINDOW_CHANGED -> {
                val pkg = o.packageName?.takeIf { it.isNotBlank() } ?: return "empty_package"
                val key = "$pkg|${o.screenId.orEmpty()}|${o.entities["kind"].orEmpty()}"
                if (key == lastWindowKey &&
                    (o.timestampMillis - lastWindowAtMillis) < config.windowCoalesceMs
                ) {
                    return "window_coalesce"
                }
                // Сразу после FG того же пакета — window_state шум
                if (pkg == lastFgPackage &&
                    o.entities["kind"] == "window_state" &&
                    (o.timestampMillis - lastFgAtMillis) < config.sameFgDebounceMs
                ) {
                    return "window_after_fg"
                }
            }
            ObservationType.BATTERY_CHANGED -> {
                val key = "${o.entities["percent"]}|${o.entities["charging"]}"
                if (key == lastBatteryKey &&
                    (o.timestampMillis - lastBatteryAtMillis) < config.batteryCoalesceMs
                ) {
                    return "battery_coalesce"
                }
            }
            ObservationType.NETWORK_CHANGED -> {
                val key = o.entities["network"] ?: o.summary.orEmpty()
                if (key == lastNetworkKey &&
                    (o.timestampMillis - lastNetworkAtMillis) < config.networkCoalesceMs
                ) {
                    return "network_coalesce"
                }
            }
            ObservationType.NOTIFICATION_POSTED -> {
                val key = "${o.packageName}|${o.summary}"
                if (key == lastNotifKey &&
                    (o.timestampMillis - lastNotifAtMillis) < config.notificationDebounceMs
                ) {
                    return "notif_debounce"
                }
            }
            else -> Unit
        }
        return null
    }

    private fun rememberAccepted(o: PhoneObservation) {
        when (o.type) {
            ObservationType.APP_FOREGROUND -> {
                lastFgPackage = o.packageName
                lastFgAtMillis = o.timestampMillis
            }
            ObservationType.WINDOW_CHANGED -> {
                lastWindowKey = "${o.packageName}|${o.screenId.orEmpty()}|${o.entities["kind"].orEmpty()}"
                lastWindowAtMillis = o.timestampMillis
            }
            ObservationType.BATTERY_CHANGED -> {
                lastBatteryKey = "${o.entities["percent"]}|${o.entities["charging"]}"
                lastBatteryAtMillis = o.timestampMillis
            }
            ObservationType.NETWORK_CHANGED -> {
                lastNetworkKey = o.entities["network"] ?: o.summary.orEmpty()
                lastNetworkAtMillis = o.timestampMillis
            }
            ObservationType.NOTIFICATION_POSTED -> {
                lastNotifKey = "${o.packageName}|${o.summary}"
                lastNotifAtMillis = o.timestampMillis
            }
            else -> Unit
        }
    }

    private fun apply(prev: PhoneContext, o: PhoneObservation): PhoneContext {
        var next = prev.copy(
            lastObservationSummary = o.summary ?: o.type.name,
            lastObservationAtMillis = o.timestampMillis,
            observationCount = prev.observationCount + 1,
        )
        when (o.type) {
            ObservationType.SCREEN_ON -> next = next.copy(screenOn = true)
            ObservationType.SCREEN_OFF -> next = next.copy(screenOn = false)
            ObservationType.BATTERY_CHANGED -> {
                val pct = o.entities["percent"]?.toIntOrNull()
                val charging = o.entities["charging"]?.toBooleanStrictOrNull()
                next = next.copy(
                    batteryPercent = pct ?: prev.batteryPercent,
                    charging = charging ?: prev.charging,
                )
            }
            ObservationType.CHARGER_CONNECTED -> next = next.copy(charging = true)
            ObservationType.CHARGER_DISCONNECTED -> next = next.copy(charging = false)
            ObservationType.HEADPHONES_CONNECTED -> next = next.copy(headphonesConnected = true)
            ObservationType.HEADPHONES_DISCONNECTED -> next = next.copy(headphonesConnected = false)
            ObservationType.NETWORK_CHANGED ->
                next = next.copy(networkType = o.entities["network"] ?: o.summary)
            ObservationType.APP_FOREGROUND -> {
                val pkg = o.packageName?.takeIf { it.isNotBlank() }
                if (pkg != null) {
                    next = if (pkg != prev.currentApp) {
                        next.copy(
                            previousApp = prev.currentApp,
                            currentApp = pkg,
                            currentAppSource = o.source,
                            currentAppConfidence = o.confidence,
                            currentAppAtMillis = o.timestampMillis,
                        )
                    } else {
                        next.copy(
                            currentApp = pkg,
                            currentAppSource = o.source,
                            currentAppConfidence = o.confidence,
                            currentAppAtMillis = o.timestampMillis,
                        )
                    }
                }
            }
            ObservationType.WINDOW_CHANGED -> {
                // Не меняет currentApp, если пакет тот же — только счётчик/lastObservation.
                // Смена пакета через WINDOW без FG (редко) — обновим app.
                val pkg = o.packageName?.takeIf { it.isNotBlank() }
                if (pkg != null && pkg != prev.currentApp) {
                    next = next.copy(
                        previousApp = prev.currentApp,
                        currentApp = pkg,
                        currentAppSource = o.source,
                        currentAppConfidence = o.confidence,
                        currentAppAtMillis = o.timestampMillis,
                    )
                }
            }
            ObservationType.NOTIFICATION_POSTED -> {
                next = next.copy(
                    lastNotificationPackage = o.packageName ?: prev.lastNotificationPackage,
                    lastNotificationSummary = o.summary ?: prev.lastNotificationSummary,
                )
            }
            ObservationType.NOTIFICATION_REMOVED -> {
                if (o.packageName != null && o.packageName == prev.lastNotificationPackage) {
                    next = next.copy(
                        lastNotificationPackage = null,
                        lastNotificationSummary = null,
                    )
                }
            }
            else -> Unit
        }
        return next
    }

    companion object {
        fun ObservationSource.priority(): Int = when (this) {
            ObservationSource.ACCESSIBILITY -> 30
            ObservationSource.USAGE_STATS -> 10
            ObservationSource.SYSTEM_BROADCAST -> 20
            ObservationSource.NOTIFICATION_LISTENER -> 15
            ObservationSource.VOICE, ObservationSource.TOOL, ObservationSource.INTERNAL -> 5
        }
    }
}
