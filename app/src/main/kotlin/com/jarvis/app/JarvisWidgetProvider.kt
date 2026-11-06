package com.jarvis.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.RemoteViews
import com.jarvis.app.ui.JarvisActivity
import com.jarvis.app.ui.ReactorArt
import com.jarvis.app.ui.Skins
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import com.jarvis.core.reminder.HudText
import java.time.LocalDateTime

/** Большой виджет 4×2. Логика как на главном экране: ядро включает и выключает, микрофон начинает разговор. */
open class JarvisWidgetProvider : AppWidgetProvider() {
    protected open val layout: Int = R.layout.widget_jarvis
    protected open val coreDp: Int = 88
    protected open val compact: Boolean = false

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_BRIEF -> brief(context.applicationContext)
            ACTION_OFF -> VoiceController.stop(context.applicationContext)
        }
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

    internal fun views(context: Context): RemoteViews {
        val skin = Skins.current(context)
        val state = JarvisEvents.assistantState.value
        val on = VoiceController.isRunning
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val reminders = runCatching { AndroidReminderBook(context).all() }.getOrDefault(emptyList())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = { action: String ->
            Intent(context, JarvisActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // Включить микрофон из фона Android не даст, поэтому включение идёт через окно приложения.
        val power = if (on) {
            PendingIntent.getBroadcast(context, REQUEST_POWER, Intent(context, JarvisWidgetProvider::class.java).setAction(ACTION_OFF), flags)
        } else {
            PendingIntent.getActivity(context, REQUEST_POWER, open(JarvisActivity.ACTION_POWER_ON), flags)
        }
        val listen = PendingIntent.getActivity(context, REQUEST_LISTEN, open(Intent.ACTION_ASSIST), flags)
        val brief = PendingIntent.getBroadcast(context, REQUEST_BRIEF, Intent(context, JarvisWidgetProvider::class.java).setAction(ACTION_BRIEF), flags)
        val status = when {
            !on -> if (compact) "Выключен" else "Выключен, нажмите на ядро"
            state == AssistantState.LISTENING -> "Слушает"
            state == AssistantState.THINKING || state == AssistantState.EXECUTING -> "Думает"
            state == AssistantState.SPEAKING -> "Говорит"
            compact -> "Ожидает"
            else -> "Слушает «Джарвис», заряд $battery%"
        }
        val res = context.resources
        fun id(name: String) = res.getIdentifier(name, "drawable", context.packageName)
        return RemoteViews(context.packageName, layout).apply {
            setInt(R.id.widgetRoot, "setBackgroundResource", id("bg_widget_${skin.id}"))
            setInt(R.id.widgetListen, "setBackgroundResource", id("bg_widget_main_${skin.id}"))
            setInt(R.id.widgetMicIcon, "setColorFilter", skin.text)
            setImageViewBitmap(R.id.widgetCore, ReactorArt.render(context, skin, coreDp, on, state == AssistantState.SPEAKING))
            setTextViewText(R.id.widgetStatus, status)
            setTextColor(R.id.widgetStatus, if (on) skin.good else skin.muted)
            setTextViewText(R.id.widgetNext, HudText.next(reminders, LocalDateTime.now()))
            setTextColor(R.id.widgetNext, skin.text)
            setTextViewText(R.id.widgetSub, JarvisSettings.prefs(context).getString(PREF_WEATHER, "").orEmpty())
            setTextColor(R.id.widgetSub, skin.muted)
            setTextColor(R.id.widgetBrand, skin.text)
            setTextColor(R.id.widgetBrief, skin.text)
            setTextColor(R.id.widgetListenText, skin.text)
            setOnClickPendingIntent(R.id.widgetCore, power)
            setOnClickPendingIntent(R.id.widgetListen, listen)
            setOnClickPendingIntent(R.id.widgetBrief, brief)
        }
    }

    companion object {
        private const val ACTION_BRIEF = "com.jarvis.assistant.WIDGET_BRIEF"
        private const val ACTION_OFF = "com.jarvis.assistant.WIDGET_OFF"
        private const val BRIEF_NOTIFICATION = 9_000L
        private const val REQUEST_POWER = 10
        private const val REQUEST_LISTEN = 11
        private const val REQUEST_BRIEF = 12
        const val PREF_WEATHER = "widget_weather"

        /** Обновить виджеты всех размеров после смены состояния. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            listOf(JarvisWidgetProvider(), JarvisWidgetSquare(), JarvisWidgetStrip()).forEach { provider ->
                val ids = manager.getAppWidgetIds(ComponentName(app, provider.javaClass))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, provider.views(app))
            }
        }
    }
}

/** Квадрат 2×2. */
class JarvisWidgetSquare : JarvisWidgetProvider() {
    override val layout = R.layout.widget_jarvis_square
    override val coreDp = 84
    override val compact = true
}

/** Полоска 4×1. */
class JarvisWidgetStrip : JarvisWidgetProvider() {
    override val layout = R.layout.widget_jarvis_strip
    override val coreDp = 46
}
