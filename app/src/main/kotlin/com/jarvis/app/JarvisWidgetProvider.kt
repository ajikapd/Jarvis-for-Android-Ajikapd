package com.jarvis.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.RemoteViews
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.reminder.HudText
import java.time.LocalDateTime

/** Виджет на главном экране: состояние, ближайшее напоминание, две кнопки. */
class JarvisWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_BRIEF) brief(context.applicationContext)
    }

    /** Сводка идёт в сеть, поэтому не в главном потоке. */
    private fun brief(context: Context) {
        val pending = goAsync()
        Thread({
            try {
                val reply = BrainGraph.build(context).process(UserRequest("сводка на день")).replyText
                ReminderReceiver.deliver(context, BRIEF_NOTIFICATION, reply)
            } finally {
                pending.finish()
            }
        }, "jarvis-widget-brief").start()
    }

    private fun views(context: Context): RemoteViews {
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val reminders = runCatching { AndroidReminderBook(context).all() }.getOrDefault(emptyList())
        val listen = Intent(context, com.jarvis.app.ui.JarvisActivity::class.java).setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val brief = Intent(context, JarvisWidgetProvider::class.java).setAction(ACTION_BRIEF)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return RemoteViews(context.packageName, R.layout.widget_jarvis).apply {
            setTextViewText(R.id.widgetStatus, HudText.status(VoiceController.isRunning, battery))
            setTextViewText(R.id.widgetNext, HudText.next(reminders, LocalDateTime.now()))
            setOnClickPendingIntent(R.id.widgetListen, PendingIntent.getActivity(context, 0, listen, flags))
            setOnClickPendingIntent(R.id.widgetBrief, PendingIntent.getBroadcast(context, 1, brief, flags))
        }
    }

    companion object {
        private const val ACTION_BRIEF = "com.jarvis.assistant.WIDGET_BRIEF"
        private const val BRIEF_NOTIFICATION = 9_000L

        /** Обновить все виджеты после смены состояния. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(app, JarvisWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, JarvisWidgetProvider().views(app))
        }
    }
}
