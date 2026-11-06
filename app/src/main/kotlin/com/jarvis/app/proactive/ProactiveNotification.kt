package com.jarvis.app.proactive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.app.DashboardActivity
import com.jarvis.core.proactive.ProactiveDeliveryContract
import com.jarvis.core.proactive.ProactiveText
import com.jarvis.app.R

/**
 * Б3: беззвучное уведомление о проактивном предложении.
 *
 * Канал IMPORTANCE_DEFAULT, но без звука и вибрации; само уведомление
 * setSilent(true). На экране блокировки показывается нейтральный publicVersion
 * без текста предложения.
 */
object ProactiveNotification {

    private const val BASE_ID = 4_100
    private const val PUBLIC_TITLE = "Jarvis"
    private const val PUBLIC_TEXT = "Jarvis: есть предложение"

    fun ensureChannel(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val channel = NotificationChannel(
            ProactiveDeliveryContract.CHANNEL_ID,
            context.getString(R.string.proactive_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.proactive_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
        return manager
    }

    /** Показывает уведомление. true — если notify() не бросил исключение. */
    fun show(
        context: Context,
        deliveryId: String,
        text: ProactiveText,
        patternKey: String?,
        fingerprint: String?,
    ): Boolean {
        if (text.title.isBlank() && text.body.isBlank()) return false
        val manager = ensureChannel(context) ?: return false
        val open = PendingIntent.getActivity(
            context,
            requestCode(deliveryId, "open"),
            Intent(context, com.jarvis.app.ui.JarvisActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(
            context,
            ProactiveDeliveryContract.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                0,
                context.getString(R.string.proactive_action_yes),
                action(context, deliveryId, patternKey, fingerprint, ProactiveActionReceiver.ACTION_YES),
            )
            .addAction(
                0,
                context.getString(R.string.proactive_action_no),
                action(context, deliveryId, patternKey, fingerprint, ProactiveActionReceiver.ACTION_NO),
            )
            .addAction(
                0,
                context.getString(R.string.proactive_action_later),
                action(context, deliveryId, patternKey, fingerprint, ProactiveActionReceiver.ACTION_LATER),
            )

        val publicVersion = NotificationCompat.Builder(
            context,
            ProactiveDeliveryContract.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(PUBLIC_TITLE)
            .setContentText(PUBLIC_TEXT)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        builder.setPublicVersion(publicVersion)

        return runCatching {
            manager.notify(notificationId(deliveryId), builder.build())
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, deliveryId: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(notificationId(deliveryId))
        }
    }

    fun notificationId(deliveryId: String): Int =
        BASE_ID + (deliveryId.hashCode() and 0xFF)

    private fun action(
        context: Context,
        deliveryId: String,
        patternKey: String?,
        fingerprint: String?,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, ProactiveActionReceiver::class.java).apply {
            this.action = action
            putExtra(ProactiveActionReceiver.EXTRA_DELIVERY_ID, deliveryId)
            putExtra(ProactiveActionReceiver.EXTRA_PATTERN_KEY, patternKey)
            putExtra(ProactiveActionReceiver.EXTRA_FINGERPRINT, fingerprint)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(deliveryId, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(deliveryId: String, action: String): Int =
        (deliveryId.hashCode() * 31 + action.hashCode()) and 0x7FFF_FFFF
}
