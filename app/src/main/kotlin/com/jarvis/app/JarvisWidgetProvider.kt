package com.jarvis.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.view.View
import android.widget.RemoteViews
import com.jarvis.app.ui.JarvisActivity
import com.jarvis.app.ui.ReactorArt
import com.jarvis.app.ui.Skin
import com.jarvis.app.ui.Skins
import com.jarvis.app.ui.WidgetArt
import com.jarvis.app.ui.stateTone
import com.jarvis.core.startup.UiStyle
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
    /** Размер по умолчанию, пока система не сообщила настоящий (dp). */
    protected open val defaultW: Int = 250
    protected open val defaultH: Int = 110

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, manager, it)) }
    }

    /** Виджет растянули или сжали: фон рисуется под новый размер. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        manager.updateAppWidget(id, views(context, manager, id))
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

    /** [styleOverride] — только для отладочной картинки виджета; обычно стиль берётся из настроек. */
    internal fun views(context: Context, manager: AppWidgetManager, id: Int, styleOverride: UiStyle? = null): RemoteViews {
        val style = styleOverride ?: UiMode.style(context) ?: UiStyle.MODERN
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
        val views = RemoteViews(context.packageName, layout)
        // Клики одинаковые во всех стилях.
        views.setOnClickPendingIntent(R.id.widgetCore, power)
        views.setOnClickPendingIntent(R.id.widgetListen, listen)
        views.setOnClickPendingIntent(R.id.widgetBrief, brief)
        views.setTextViewText(R.id.widgetStatus, status)
        views.setTextViewText(R.id.widgetNext, HudText.next(reminders, LocalDateTime.now()))
        views.setTextViewText(R.id.widgetSub, JarvisSettings.prefs(context).getString(PREF_WEATHER, "").orEmpty())
        val opts = manager.getAppWidgetOptions(id)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: defaultW
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: defaultH
        val shownState = if (on) state else AssistantState.OFFLINE
        when (style) {
            UiStyle.PREMIUM -> premium(context, views, skin, shownState, on, wDp, hDp)
            UiStyle.CLASSIC -> classic(context, views, shownState, on, wDp, hDp)
            UiStyle.MODERN -> modern(context, views, skin, state, on)
        }
        return views
    }

    /** Современный: как было — фон и реактор по теме современного интерфейса. */
    private fun modern(context: Context, v: RemoteViews, skin: Skin, state: AssistantState, on: Boolean) {
        val res = context.resources
        fun id(name: String) = res.getIdentifier(name, "drawable", context.packageName)
        v.setViewVisibility(R.id.widgetBg, View.GONE)
        v.setInt(R.id.widgetRoot, "setBackgroundResource", id("bg_widget_${skin.id}").takeIf { it != 0 } ?: R.drawable.bg_widget_reactor)
        v.setInt(R.id.widgetListen, "setBackgroundResource", id("bg_widget_main_${skin.id}").takeIf { it != 0 } ?: R.drawable.bg_widget_main_reactor)
        v.setInt(R.id.widgetBrief, "setBackgroundResource", R.drawable.bg_widget_btn)
        v.setInt(R.id.widgetMicIcon, "setColorFilter", skin.text)
        v.setImageViewBitmap(R.id.widgetCore, ReactorArt.render(context, skin, coreDp, on, state == AssistantState.SPEAKING))
        v.setTextViewText(R.id.widgetBrand, "J.A.R.V.I.S.")
        v.setTextColor(R.id.widgetStatus, if (on) skin.good else skin.muted)
        v.setTextColor(R.id.widgetNext, skin.text)
        v.setTextColor(R.id.widgetSub, skin.muted)
        v.setTextColor(R.id.widgetBrand, skin.text)
        v.setTextColor(R.id.widgetBrief, skin.text)
        v.setTextColor(R.id.widgetListenText, skin.text)
    }

    /** Премиум: дым и стекло в цвет схемы, ядро из частиц, серебряная кнопка. */
    private fun premium(context: Context, v: RemoteViews, skin: Skin, state: AssistantState, on: Boolean, wDp: Int, hDp: Int) {
        v.setViewVisibility(R.id.widgetBg, View.VISIBLE)
        v.setImageViewBitmap(R.id.widgetBg, WidgetArt.premiumBackground(context, skin, state, wDp, hDp))
        v.setInt(R.id.widgetRoot, "setBackgroundResource", 0)
        v.setInt(R.id.widgetListen, "setBackgroundResource", R.drawable.bg_widget_main_premium)
        v.setInt(R.id.widgetBrief, "setBackgroundResource", R.drawable.bg_widget_btn_premium)
        v.setInt(R.id.widgetMicIcon, "setColorFilter", skin.onAccent)
        v.setImageViewBitmap(R.id.widgetCore, WidgetArt.particleCore(context, skin, coreDp, state))
        v.setTextViewText(R.id.widgetBrand, "JARVIS")
        v.setTextColor(R.id.widgetStatus, if (on) stateTone(skin, state) else skin.muted)
        v.setTextColor(R.id.widgetNext, skin.text)
        v.setTextColor(R.id.widgetSub, skin.muted)
        v.setTextColor(R.id.widgetBrand, skin.text)
        v.setTextColor(R.id.widgetBrief, skin.text)
        v.setTextColor(R.id.widgetListenText, skin.onAccent)
    }

    /** Классический: панель консоли и реактор в цветах темы классического интерфейса. */
    private fun classic(context: Context, v: RemoteViews, state: AssistantState, on: Boolean, wDp: Int, hDp: Int) {
        val p = ThemeApplier.palette(JarvisSettings.theme(context))
        v.setViewVisibility(R.id.widgetBg, View.VISIBLE)
        v.setImageViewBitmap(R.id.widgetBg, WidgetArt.classicBackground(context, p, wDp, hDp))
        v.setInt(R.id.widgetRoot, "setBackgroundResource", 0)
        v.setInt(R.id.widgetListen, "setBackgroundResource", R.drawable.bg_widget_btn_classic)
        v.setInt(R.id.widgetBrief, "setBackgroundResource", R.drawable.bg_widget_btn_classic)
        v.setInt(R.id.widgetMicIcon, "setColorFilter", p.primary)
        v.setImageViewBitmap(R.id.widgetCore, WidgetArt.classicCore(context, p, coreDp, state))
        v.setTextViewText(R.id.widgetBrand, "J.A.R.V.I.S.")
        v.setTextColor(R.id.widgetStatus, if (on) p.green else p.muted)
        v.setTextColor(R.id.widgetNext, p.text)
        v.setTextColor(R.id.widgetSub, p.muted)
        v.setTextColor(R.id.widgetBrand, p.primary)
        v.setTextColor(R.id.widgetBrief, p.text)
        v.setTextColor(R.id.widgetListenText, p.primary)
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
                ids.forEach { id -> runCatching { manager.updateAppWidget(id, provider.views(app, manager, id)) } }
            }
        }
    }
}

/** Квадрат 2×2. */
class JarvisWidgetSquare : JarvisWidgetProvider() {
    override val layout = R.layout.widget_jarvis_square
    override val coreDp = 84
    override val compact = true
    override val defaultW = 110
    override val defaultH = 110
}

/** Полоска 4×1. */
class JarvisWidgetStrip : JarvisWidgetProvider() {
    override val layout = R.layout.widget_jarvis_strip
    override val coreDp = 46
    override val defaultH = 50
}
