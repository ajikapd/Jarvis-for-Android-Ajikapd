package com.jarvis.core.proactive

/**
 * Б1: чистое (без Android) состояние телефона, от которого зависит доставка
 * проактивного предложения. Заполняется в app-слое [com.jarvis.app.proactive.PhoneStateReader].
 */
data class PhoneStateSnapshot(
    val ringerMode: RingerKind,
    val interruptionFilter: InterruptionKind,
    val callActive: Boolean,
    val jarvisSessionActive: Boolean,
    val jarvisSpeaking: Boolean,
    val notificationsAllowed: Boolean,
    val voiceAvailable: Boolean,
)

/** Режим звонка/уведомлений в терминах AudioManager.ringerMode. */
enum class RingerKind {
    NORMAL,
    VIBRATE,
    SILENT,
}

/** Фильтр «Не беспокоить» в терминах NotificationManager.currentInterruptionFilter. */
enum class InterruptionKind {
    ALL,
    PRIORITY,
    ALARMS,
    NONE,
    UNKNOWN,
}

/** Что делать с конкретным предложением. */
enum class DeliveryMode {
    SUPPRESS,
    SILENT_NOTIFICATION_ONLY,
    VOICE_AND_NOTIFICATION,
}

/** Причина решения — для журнала доставки, пользователю не показывается. */
enum class DeliveryReason {
    NO_OP,
    NOTIFICATIONS_DISABLED,
    JARVIS_SESSION_ACTIVE,
    JARVIS_SPEAKING,
    CALL_ACTIVE,
    RINGER_SILENT,
    RINGER_VIBRATE,
    DND_PRIORITY,
    DND_ALARMS,
    DND_NONE,
    DND_UNKNOWN,
    VOICE_UNAVAILABLE,
    READY,

    /** delivery-слой: такое предложение уже показывали. */
    DUPLICATE,

    /** delivery-слой: пользователь нажал «Позже», отсрочка ещё идёт. */
    SNOOZED,
}

data class DeliveryDecision(
    val mode: DeliveryMode,
    val reason: DeliveryReason,
)

/** Идентификатор канала проактивных уведомлений (создаётся в app-слое). */
object ProactiveDeliveryContract {
    const val CHANNEL_ID: String = "jarvis_proactive"
}

/**
 * Чистая таблица решений Б1. Порядок проверок зафиксирован заказчиком:
 * NO_OP → уведомления выключены → сессия/речь Jarvis → звонок → без звука/
 * вибрация → DND → нет голоса → голос + уведомление.
 */
object DeliveryDecider {

    fun decide(type: ProactiveDecisionType, state: PhoneStateSnapshot): DeliveryDecision = when {
        type == ProactiveDecisionType.NO_OP ->
            DeliveryDecision(DeliveryMode.SUPPRESS, DeliveryReason.NO_OP)

        !state.notificationsAllowed ->
            DeliveryDecision(DeliveryMode.SUPPRESS, DeliveryReason.NOTIFICATIONS_DISABLED)

        state.jarvisSessionActive ->
            DeliveryDecision(DeliveryMode.SUPPRESS, DeliveryReason.JARVIS_SESSION_ACTIVE)

        state.jarvisSpeaking ->
            DeliveryDecision(DeliveryMode.SUPPRESS, DeliveryReason.JARVIS_SPEAKING)

        state.callActive ->
            DeliveryDecision(DeliveryMode.SILENT_NOTIFICATION_ONLY, DeliveryReason.CALL_ACTIVE)

        state.ringerMode == RingerKind.SILENT ->
            DeliveryDecision(DeliveryMode.SILENT_NOTIFICATION_ONLY, DeliveryReason.RINGER_SILENT)

        state.ringerMode == RingerKind.VIBRATE ->
            DeliveryDecision(DeliveryMode.SILENT_NOTIFICATION_ONLY, DeliveryReason.RINGER_VIBRATE)

        state.interruptionFilter != InterruptionKind.ALL ->
            DeliveryDecision(
                DeliveryMode.SILENT_NOTIFICATION_ONLY,
                when (state.interruptionFilter) {
                    InterruptionKind.PRIORITY -> DeliveryReason.DND_PRIORITY
                    InterruptionKind.ALARMS -> DeliveryReason.DND_ALARMS
                    InterruptionKind.NONE -> DeliveryReason.DND_NONE
                    else -> DeliveryReason.DND_UNKNOWN
                },
            )

        !state.voiceAvailable ->
            DeliveryDecision(DeliveryMode.SILENT_NOTIFICATION_ONLY, DeliveryReason.VOICE_UNAVAILABLE)

        else ->
            DeliveryDecision(DeliveryMode.VOICE_AND_NOTIFICATION, DeliveryReason.READY)
    }
}
