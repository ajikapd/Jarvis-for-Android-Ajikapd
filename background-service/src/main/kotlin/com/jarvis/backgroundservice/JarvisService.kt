package com.jarvis.backgroundservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service — сердце Jarvis.
 *
 * Живёт постоянно, переживает выключение экрана и сворачивание приложений.
 * На Этапе 1 просто держит процесс живым и публикует STARTED-событие;
 * micro-движки (wake word, ASR, TTS...) подключаются поэтапно.
 *
 * Тип foregroundServiceType = microphone — обязателен для фоновой записи
 * микрофона (Андроид 14+). Сам сервис записи на Этапе 1 не ведёт,
 * но тип фиксируется сейчас, чтобы не менять позже.
 */
class JarvisService : Service() {

    companion object {
        private const val CHANNEL_ID = "jarvis_service_channel"
        private const val NOTIFICATION_ID = 1001

        /** Запустить сервис из любого контекста (Activity, Receiver, Worker). */
        fun start(context: Context) {
            val intent = Intent(context, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Остановить сервис. */
        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch {
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.READY))
            JarvisEvents.publish(
                AssistantEvent.ServiceStarted("jarvis-fg-up-${System.currentTimeMillis()}")
            )
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.launch {
            JarvisEvents.publish(AssistantEvent.StateChanged(AssistantState.OFFLINE))
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (buildVersionAtLeast(Build.VERSION_CODES.O)) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное уведомление фонового ассистента"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Открываем MainActivity при тапе на уведомление
        val launchIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?: Intent()
        val pending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText("Ассистент активен")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun buildVersionAtLeast(code: Int) = Build.VERSION.SDK_INT >= code
}