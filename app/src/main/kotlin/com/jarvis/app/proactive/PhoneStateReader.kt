package com.jarvis.app.proactive

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.jarvis.app.VoiceController
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import com.jarvis.core.proactive.InterruptionKind
import com.jarvis.core.proactive.PhoneStateSnapshot
import com.jarvis.core.proactive.ProactiveDeliveryContract
import com.jarvis.core.proactive.RingerKind

/**
 * Б1: чтение реального состояния телефона (только чтение, ничего не меняет).
 *
 * Все системные вызовы обёрнуты в runCatching: на HyperOS отдельные поля могут
 * быть недоступны без разрешения — тогда используется безопасный дефолт
 * (например, «Не беспокоить» → UNKNOWN → доставка только уведомлением).
 */
object PhoneStateReader {

    /** Значения --es fakeState для debug-проверок. Реальные настройки не трогаются. */
    const val FAKE_NONE = "none"
    const val FAKE_SILENT = "silent"
    const val FAKE_DND = "dnd"
    const val FAKE_CALL = "call"
    const val FAKE_SESSION = "session"
    const val FAKE_NO_NOTIFICATIONS = "nonotif"
    const val FAKE_NO_VOICE = "novoice"

    fun read(context: Context): PhoneStateSnapshot {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val notifications = app.getSystemService(NotificationManager::class.java)

        val ringer = when (runCatching { audio?.ringerMode }.getOrNull()) {
            AudioManager.RINGER_MODE_SILENT -> RingerKind.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerKind.VIBRATE
            else -> RingerKind.NORMAL
        }

        val filter = when (runCatching { notifications?.currentInterruptionFilter }.getOrNull()) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> InterruptionKind.ALL
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> InterruptionKind.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> InterruptionKind.ALARMS
            NotificationManager.INTERRUPTION_FILTER_NONE -> InterruptionKind.NONE
            else -> InterruptionKind.UNKNOWN
        }

        val call = isCallActive(app, audio)

        val assistant = JarvisEvents.assistantState.value
        val session = VoiceController.isLiveSession ||
            assistant == AssistantState.LISTENING ||
            assistant == AssistantState.THINKING ||
            assistant == AssistantState.SPEAKING

        val notificationsAllowed = runCatching {
            val enabled = notifications?.areNotificationsEnabled() == true
            val channel = notifications?.getNotificationChannel(
                ProactiveDeliveryContract.CHANNEL_ID,
            )
            enabled && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
        }.getOrDefault(false)

        return PhoneStateSnapshot(
            ringerMode = ringer,
            interruptionFilter = filter,
            callActive = call,
            jarvisSessionActive = session,
            jarvisSpeaking = VoiceController.isSpeaking,
            notificationsAllowed = notificationsAllowed,
            voiceAvailable = VoiceController.hasEngine,
        )
    }

    /**
     * Подмена полей снимка для debug-матрицы. Меняется ровно одно измерение;
     * остальные остаются реальными. Неизвестное значение — снимок без изменений.
     */
    fun applyFake(base: PhoneStateSnapshot, fakeState: String?): PhoneStateSnapshot =
        when (fakeState?.trim()?.lowercase()) {
            FAKE_SILENT -> base.copy(ringerMode = RingerKind.SILENT)
            FAKE_DND -> base.copy(interruptionFilter = InterruptionKind.PRIORITY)
            FAKE_CALL -> base.copy(callActive = true)
            FAKE_SESSION -> base.copy(jarvisSessionActive = true)
            FAKE_NO_NOTIFICATIONS -> base.copy(notificationsAllowed = false)
            FAKE_NO_VOICE -> base.copy(voiceAvailable = false)
            FAKE_NONE, null, "" -> base
            else -> base
        }

    fun knownFakeStates(): Set<String> = setOf(
        FAKE_NONE,
        FAKE_SILENT,
        FAKE_DND,
        FAKE_CALL,
        FAKE_SESSION,
        FAKE_NO_NOTIFICATIONS,
        FAKE_NO_VOICE,
    )

    @Suppress("DEPRECATION")
    private fun isCallActive(context: Context, audio: AudioManager?): Boolean {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val phoneState = if (
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { telephony?.callState }.getOrNull()
        } else {
            null
        }
        val telephonyCall = phoneState == TelephonyManager.CALL_STATE_RINGING ||
            phoneState == TelephonyManager.CALL_STATE_OFFHOOK
        val audioMode = runCatching { audio?.mode }.getOrNull()
        val audioCall = audioMode == AudioManager.MODE_IN_CALL ||
            audioMode == AudioManager.MODE_IN_COMMUNICATION
        return telephonyCall || audioCall
    }
}
