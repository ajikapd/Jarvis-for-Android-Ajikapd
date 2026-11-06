package com.jarvis.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.app.proactive.ProactiveVoice
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.reminder.ReminderSpeech

/** Срабатывание напоминания: голосом, если контур слушает, и всегда уведомлением. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val reminder = AndroidReminderBook(context).fired(id) ?: return
        if (reminder.command) {
            runCommand(context.applicationContext, id, reminder.text)
        } else {
            deliver(context, id, ReminderSpeech.firing(reminder.text))
        }
    }

    /** Команда может идти в сеть: выполняем вне главного потока, приёмник держим живым. */
    private fun runCommand(context: Context, id: Long, command: String) {
        val pending = goAsync()
        Thread({
            try {
                val reply = BrainGraph.build(context).process(UserRequest(command)).replyText
                deliver(context, id, reply)
            } catch (t: Throwable) {
                Log.w(TAG, "scheduled command failed: ${t.message}", t)
                deliver(context, id, "Не удалось выполнить, сэр: $command.")
            } finally {
                pending.finish()
            }
        }, "jarvis-scheduled").start()
    }

    private fun deliver(context: Context, id: Long, text: String) {
        val spoken = ProactiveVoice.speak(context, text).requested
        notify(context, id, text, silent = spoken)
        VoiceHealthLog.write(context, "reminder fired id=$id spoken=$spoken")
    }

    private fun notify(context: Context, id: Long, text: String, silent: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(BASE_NOTIFICATION + id.toInt(), note) }
    }

    companion object {
        private const val TAG = "Jarvis.Reminders"
        private const val ACTION = "com.jarvis.assistant.REMINDER_FIRE"
        private const val EXTRA_ID = "id"
        private const val CHANNEL = "jarvis_reminders"
        private const val BASE_NOTIFICATION = 5_000

        fun intent(context: Context, id: Long): Intent =
            Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id)
    }
}
