package com.jarvis.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.jarvis.app.pro.ProActivity
import com.jarvis.app.ui.Device
import com.jarvis.app.ui.JarvisActivity
import com.jarvis.app.ui.ReactorArt
import com.jarvis.app.ui.Skin
import com.jarvis.app.ui.Skins
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import com.jarvis.core.reminder.HudText
import com.jarvis.screencontrol.JarvisNotificationListenerService
import com.jarvis.tools.MusicTool
import com.jarvis.tools.WeatherTool
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Рабочие виджеты Pro:
 * 1) Ядро / квадрат / полоска — порт обычного Джарвиса под Pro UI.
 * 2) Сводка на экране — уведомления, погода, заряд, дело, дела, музыка.
 */
internal object ProHomeWidgets {
    const val PREF_WEATHER = "widget_weather"
    private const val PREF_WEATHER_AT = "widget_weather_at"
    private const val WEATHER_TTL_MS = 25 * 60_000L
    private const val TAG = "Jarvis.Widget"
    private const val ACTION_CMD = "com.jarvis.assistant.WIDGET_CMD"
    private const val ACTION_MUSIC = "com.jarvis.assistant.WIDGET_MUSIC"
    private const val ACTION_WEATHER = "com.jarvis.assistant.WIDGET_WEATHER"
    private const val EXTRA_CMD = "cmd"
    private const val EXTRA_MUSIC = "music"

    private val weatherBusy = AtomicBoolean(false)

    private val allProviders = listOf(
        JarvisWidgetProvider::class.java,
        JarvisWidgetSquare::class.java,
        JarvisWidgetStrip::class.java,
        JarvisWidgetNotifs::class.java,
        JarvisWidgetWeather::class.java,
        JarvisWidgetBattery::class.java,
        JarvisWidgetNext::class.java,
        JarvisWidgetTasks::class.java,
        JarvisWidgetMusic::class.java,
    )

    fun refresh(context: Context) {
        val app = context.applicationContext
        ensureWeather(app, force = false)
        val manager = AppWidgetManager.getInstance(app) ?: return
        allProviders.forEach { cls ->
            runCatching {
                val ids = manager.getAppWidgetIds(ComponentName(app, cls))
                if (ids.isEmpty()) return@runCatching
                val provider = cls.getDeclaredConstructor().newInstance()
                when (provider) {
                    is JarvisWidgetProvider -> ids.forEach { id ->
                        manager.updateAppWidget(id, provider.views(app))
                    }
                    is ProSummaryWidget -> manager.updateAppWidget(ids, provider.build(app))
                }
            }.onFailure { Log.e(TAG, "refresh ${cls.simpleName}: ${it.message}", it) }
        }
    }

    fun skinBg(context: Context): Int {
        val skin = Skins.current(context)
        val res = context.resources
        fun id(name: String) = res.getIdentifier(name, "drawable", context.packageName)
        return id("bg_widget_${skin.id}").takeIf { it != 0 }
            ?: id("bg_widget_pro_gold").takeIf { it != 0 }
            ?: R.drawable.bg_widget_reactor
    }

    fun skinBtn(context: Context): Int {
        val skin = Skins.current(context)
        val res = context.resources
        fun id(name: String) = res.getIdentifier(name, "drawable", context.packageName)
        return id("bg_widget_main_${skin.id}").takeIf { it != 0 }
            ?: id("bg_widget_main_pro_gold").takeIf { it != 0 }
            ?: R.drawable.bg_widget_main_reactor
    }

    fun skinOutline(context: Context): Int {
        val res = context.resources
        val id = res.getIdentifier("bg_widget_pro_btn", "drawable", context.packageName)
        return id.takeIf { it != 0 } ?: R.drawable.bg_widget_btn
    }

    fun openPro(context: Context, route: String? = null, action: String? = null, request: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val i = Intent(context, ProActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!action.isNullOrBlank()) i.action = action
        if (!route.isNullOrBlank()) i.putExtra(ProActivity.EXTRA_OPEN, route)
        return PendingIntent.getActivity(context, request, i, flags)
    }

    fun cmdPending(context: Context, cls: Class<out AppWidgetProvider>, cmd: String, request: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, request,
            Intent(context, cls).setAction(ACTION_CMD).putExtra(EXTRA_CMD, cmd),
            flags,
        )
    }

    fun musicPending(context: Context, cls: Class<out AppWidgetProvider>, action: String, request: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, request,
            Intent(context, cls).setAction(ACTION_MUSIC).putExtra(EXTRA_MUSIC, action),
            flags,
        )
    }

    fun weatherPending(context: Context, cls: Class<out AppWidgetProvider>, request: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, request,
            Intent(context, cls).setAction(ACTION_WEATHER),
            flags,
        )
    }

    fun handleCmd(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_MUSIC -> {
                val action = intent.getStringExtra(EXTRA_MUSIC)?.trim().orEmpty()
                if (action.isEmpty()) return
                Thread({
                    runCatching { musicAction(app, action) }
                        .onFailure { Log.e(TAG, "music failed: ${it.message}", it) }
                    runCatching { refresh(app) }
                }, "jarvis-widget-music").start()
            }
            ACTION_WEATHER -> {
                Thread({
                    ensureWeather(app, force = true)
                    runCatching { refresh(app) }
                }, "jarvis-widget-weather").start()
            }
            ACTION_CMD -> {
                val cmd = intent.getStringExtra(EXTRA_CMD)?.trim().orEmpty()
                if (cmd.isEmpty()) return
                Thread({
                    runCatching {
                        if (!VoiceController.isRunning) VoiceController.start(app)
                        BrainGraph.build(app).process(UserRequest(cmd))
                    }.onFailure { Log.e(TAG, "cmd failed: ${it.message}", it) }
                    runCatching { refresh(app) }
                }, "jarvis-widget-cmd").start()
            }
        }
    }

    fun ensureWeather(context: Context, force: Boolean) {
        val prefs = JarvisSettings.prefs(context)
        val cached = prefs.getString(PREF_WEATHER, "").orEmpty()
        val at = prefs.getLong(PREF_WEATHER_AT, 0L)
        val fresh = cached.isNotBlank() && System.currentTimeMillis() - at < WEATHER_TTL_MS
        if (!force && fresh) return
        if (!weatherBusy.compareAndSet(false, true)) return
        Thread({
            try {
                val result = WeatherTool(context).execute(
                    ToolCall(requestId = "widget-weather", toolId = "weather", arguments = emptyMap()),
                )
                if (result.status != ToolResultStatus.SUCCESS) {
                    val why = result.message.orEmpty()
                    val short = when {
                        why.contains("местоположен", true) || why.contains("где вы", true) ->
                            "— — укажи город в настройках или разреши геолокацию"
                        why.contains("геолокац", true) ->
                            "— — включи геолокацию или укажи город"
                        why.isNotBlank() -> "— — ${why.take(60)}"
                        else -> "— — нет данных о погоде"
                    }
                    if (cached.isBlank() || cached.startsWith("—")) {
                        prefs.edit().putString(PREF_WEATHER, short).putLong(PREF_WEATHER_AT, 0L).apply()
                    }
                    return@Thread
                }
                val message = result.message.orEmpty()
                val temp = (result.data["temp"] as? Number)?.toInt()
                    ?: Regex("сейчас (-?\\d+)°").find(message)?.groupValues?.get(1)?.toIntOrNull()
                val city = result.data["city"]?.toString().orEmpty()
                val desc = message.split(". ").getOrNull(1)?.trim().orEmpty()
                    .ifBlank { message.substringAfter('.').trim().take(48) }
                val degrees = when {
                    temp == null -> "—"
                    temp > 0 -> "+$temp°"
                    else -> "$temp°"
                }
                val head = if (city.isNotBlank()) "$degrees · $city" else degrees
                val line = if (desc.isNotBlank()) "$head — ${desc.lowercase()}" else head
                prefs.edit()
                    .putString(PREF_WEATHER, line)
                    .putLong(PREF_WEATHER_AT, System.currentTimeMillis())
                    .apply()
            } catch (t: Throwable) {
                Log.e(TAG, "weather: ${t.message}", t)
            } finally {
                weatherBusy.set(false)
            }
        }, "jarvis-widget-wx").start()
    }

    fun weatherLine(context: Context): String {
        ensureWeather(context, force = false)
        return JarvisSettings.prefs(context).getString(PREF_WEATHER, "").orEmpty()
            .ifBlank { "Обновляю погоду…" }
    }

    data class MusicNow(val title: String, val sub: String, val playing: Boolean)

    fun musicNow(context: Context): MusicNow {
        val result = runCatching {
            MusicTool(context).execute(
                ToolCall(requestId = "widget-music", toolId = "music", arguments = mapOf("action" to "status")),
            )
        }.getOrNull()
        val text = result?.message.orEmpty()
        if (text.isBlank() || text.contains("ничего не играет", ignoreCase = true)) {
            return MusicNow("Ничего не играет", "Нажми ▶ или скажи «включи музыку»", false)
        }
        val playing = text.contains("играет", ignoreCase = true) && !text.contains("на паузе", ignoreCase = true)
        val who = text.substringAfter(':').trim().trimEnd('.')
            .ifBlank { text }
        return MusicNow(
            title = who.take(42).ifBlank { "Музыка" },
            sub = if (playing) "Играет" else "На паузе",
            playing = playing,
        )
    }

    fun musicAction(context: Context, action: String) {
        val tool = MusicTool(context)
        val act = when (action) {
            "toggle" -> {
                val now = musicNow(context)
                if (now.playing) "pause" else "resume"
            }
            "prev" -> "previous"
            "next" -> "next"
            "pause" -> "pause"
            "resume", "play" -> "resume"
            else -> action
        }
        val result = tool.execute(
            ToolCall(requestId = "widget-music-$act", toolId = "music", arguments = mapOf("action" to act)),
        )
        if (act == "resume" && result.status != ToolResultStatus.SUCCESS) {
            tool.execute(
                ToolCall(
                    requestId = "widget-music-play",
                    toolId = "music",
                    arguments = mapOf("action" to "play", "scope" to "favorites"),
                ),
            )
        }
    }

    fun batteryPct(context: Context): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: Device.battery(context)

    fun charging(context: Context): Boolean {
        val st = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    fun nextTask(context: Context): Pair<String, String> {
        val reminders = runCatching { AndroidReminderBook(context).all() }.getOrDefault(emptyList())
        val next = HudText.next(reminders, LocalDateTime.now())
        return if (next.isBlank() || next.contains("нет", ignoreCase = true)) {
            "Ближайших дел нет" to "Скажи «Джарвис, напомни…»"
        } else next to "Напоминание"
    }

    fun tasks(context: Context): List<String> =
        runCatching { AndroidReminderBook(context).all() }.getOrDefault(emptyList())
            .take(3).map { it.text.ifBlank { "Дело" } }

    fun notifs(context: Context): List<Triple<String, String, String>> {
        if (!JarvisNotificationListenerService.isConnected()) return emptyList()
        return runCatching {
            JarvisNotificationListenerService.snapshot().take(3).map { n ->
                val label = runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(n.packageName, 0),
                    ).toString()
                }.getOrDefault(n.packageName.substringAfterLast('.').take(8).uppercase())
                Triple(n.title.ifBlank { label }, n.text, label.take(4).uppercase())
            }
        }.getOrDefault(emptyList())
    }
}

/** Большой виджет 4×2 — ядро, статус, дело, кнопки. */
open class JarvisWidgetProvider : AppWidgetProvider() {
    protected open val layout: Int = R.layout.widget_jarvis
    protected open val coreDp: Int = 88
    protected open val compact: Boolean = false

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ProHomeWidgets.ensureWeather(context.applicationContext, force = false)
        ids.forEach { id ->
            runCatching { manager.updateAppWidget(id, views(context)) }
                .onFailure { Log.e("Jarvis.Widget", "onUpdate $id: ${it.message}", it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_BRIEF -> brief(context.applicationContext)
            ACTION_OFF -> VoiceController.stop(context.applicationContext)
            else -> ProHomeWidgets.handleCmd(context, intent)
        }
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            runCatching { ProHomeWidgets.refresh(context) }
        }
    }

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
        val battery = ProHomeWidgets.batteryPct(context)
        val reminders = runCatching { AndroidReminderBook(context).all() }.getOrDefault(emptyList())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val power = if (on) {
            PendingIntent.getBroadcast(
                context, REQUEST_POWER,
                Intent(context, JarvisWidgetProvider::class.java).setAction(ACTION_OFF), flags,
            )
        } else {
            ProHomeWidgets.openPro(context, action = JarvisActivity.ACTION_POWER_ON, request = REQUEST_POWER)
        }
        val listen = ProHomeWidgets.openPro(context, route = "dialog", action = Intent.ACTION_ASSIST, request = REQUEST_LISTEN)
        val brief = PendingIntent.getBroadcast(
            context, REQUEST_BRIEF,
            Intent(context, JarvisWidgetProvider::class.java).setAction(ACTION_BRIEF), flags,
        )
        val openApp = ProHomeWidgets.openPro(context, request = REQUEST_OPEN)
        val status = when {
            !on -> if (compact) "Выключен" else "Выключен — нажми на ядро"
            state == AssistantState.LISTENING -> "Слушает"
            state == AssistantState.THINKING || state == AssistantState.EXECUTING -> "Думает"
            state == AssistantState.SPEAKING -> "Говорит"
            compact -> "Ожидает"
            else -> "Слушает «Джарвис», заряд $battery%"
        }
        val bg = ProHomeWidgets.skinBg(context)
        val btn = ProHomeWidgets.skinBtn(context)
        val outline = ProHomeWidgets.skinOutline(context)
        return RemoteViews(context.packageName, layout).apply {
            runCatching { if (bg != 0) setInt(R.id.widgetRoot, "setBackgroundResource", bg) }
            runCatching { if (btn != 0) setInt(R.id.widgetListen, "setBackgroundResource", btn) }
            runCatching { if (outline != 0) setInt(R.id.widgetBrief, "setBackgroundResource", outline) }
            runCatching { setInt(R.id.widgetMicIcon, "setColorFilter", skin.text) }
            runCatching {
                setImageViewBitmap(
                    R.id.widgetCore,
                    ReactorArt.render(context, skin, coreDp, on, state == AssistantState.SPEAKING),
                )
            }
            runCatching { setTextViewText(R.id.widgetStatus, status) }
            runCatching { setTextColor(R.id.widgetStatus, if (on) skin.good else skin.muted) }
            runCatching { setTextViewText(R.id.widgetNext, HudText.next(reminders, LocalDateTime.now())) }
            runCatching { setTextColor(R.id.widgetNext, skin.text) }
            runCatching { setTextViewText(R.id.widgetSub, ProHomeWidgets.weatherLine(context)) }
            runCatching { setTextColor(R.id.widgetSub, skin.muted) }
            runCatching { setTextViewText(R.id.widgetBrand, "ДЖАРВИС PRO") }
            runCatching { setTextColor(R.id.widgetBrand, skin.accent) }
            runCatching { setTextColor(R.id.widgetBrief, skin.text) }
            runCatching { setTextColor(R.id.widgetListenText, skin.onAccent) }
            runCatching { setOnClickPendingIntent(R.id.widgetRoot, openApp) }
            runCatching { setOnClickPendingIntent(R.id.widgetCore, power) }
            runCatching { setOnClickPendingIntent(R.id.widgetListen, listen) }
            runCatching { setOnClickPendingIntent(R.id.widgetBrief, brief) }
        }
    }

    companion object {
        private const val ACTION_BRIEF = "com.jarvis.assistant.WIDGET_BRIEF"
        private const val ACTION_OFF = "com.jarvis.assistant.WIDGET_OFF"
        private const val BRIEF_NOTIFICATION = 9_000L
        private const val REQUEST_POWER = 10
        private const val REQUEST_LISTEN = 11
        private const val REQUEST_BRIEF = 12
        private const val REQUEST_OPEN = 13
        const val PREF_WEATHER = ProHomeWidgets.PREF_WEATHER
        fun refresh(context: Context) = ProHomeWidgets.refresh(context)
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

/** Базовый виджет сводки. */
abstract class ProSummaryWidget : AppWidgetProvider() {
    protected abstract val layout: Int
    protected abstract val openRoute: String
    protected abstract val requestCode: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ProHomeWidgets.ensureWeather(context.applicationContext, force = false)
        runCatching { manager.updateAppWidget(ids, build(context)) }
            .onFailure { Log.e("Jarvis.Widget", "summary onUpdate: ${it.message}", it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        ProHomeWidgets.handleCmd(context, intent)
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE &&
            intent.action != "com.jarvis.assistant.WIDGET_MUSIC" &&
            intent.action != "com.jarvis.assistant.WIDGET_WEATHER"
        ) {
            runCatching { ProHomeWidgets.refresh(context) }
        }
    }

    fun build(context: Context): RemoteViews {
        val skin = Skins.current(context)
        val bg = ProHomeWidgets.skinBg(context)
        return RemoteViews(context.packageName, layout).apply {
            runCatching { if (bg != 0) setInt(R.id.widgetRoot, "setBackgroundResource", bg) }
            runCatching {
                setOnClickPendingIntent(
                    R.id.widgetRoot,
                    ProHomeWidgets.openPro(context, openRoute, request = requestCode),
                )
            }
            runCatching { bind(context, skin, this) }
                .onFailure { Log.e("Jarvis.Widget", "bind ${javaClass.simpleName}: ${it.message}", it) }
        }
    }

    protected abstract fun bind(context: Context, skin: Skin, views: RemoteViews)
}

class JarvisWidgetNotifs : ProSummaryWidget() {
    override val layout = R.layout.widget_pro_notifs
    override val openRoute = "important"
    override val requestCode = 201

    override fun bind(context: Context, skin: Skin, views: RemoteViews) {
        val list = ProHomeWidgets.notifs(context)
        views.setTextColor(R.id.wTitle, skin.accent)
        views.setTextColor(R.id.wHint, skin.muted)
        views.setTextViewText(R.id.wTitle, "ДЖАРВИС · УВЕДОМЛЕНИЯ")
        if (!JarvisNotificationListenerService.isConnected()) {
            views.setTextViewText(R.id.wHint, "доступ")
            views.setViewVisibility(R.id.wLine1, View.VISIBLE)
            views.setTextViewText(R.id.wLine1, "Разреши чтение уведомлений в Pro → Система")
            views.setTextColor(R.id.wLine1, skin.muted)
            views.setViewVisibility(R.id.wLine2, View.GONE)
            views.setViewVisibility(R.id.wLine3, View.GONE)
            return
        }
        views.setTextViewText(R.id.wHint, if (list.isEmpty()) "тихо" else "${list.size}")
        listOf(R.id.wLine1, R.id.wLine2, R.id.wLine3).forEachIndexed { i, id ->
            if (i < list.size) {
                val (title, text, app) = list[i]
                views.setViewVisibility(id, View.VISIBLE)
                val line = "$app · $title${if (text.isNotBlank()) ": $text" else ""}"
                views.setTextViewText(id, line.take(90))
                views.setTextColor(id, skin.text)
            } else views.setViewVisibility(id, View.GONE)
        }
        if (list.isEmpty()) {
            views.setViewVisibility(R.id.wLine1, View.VISIBLE)
            views.setTextViewText(R.id.wLine1, "Новых уведомлений нет")
            views.setTextColor(R.id.wLine1, skin.muted)
        }
    }
}

class JarvisWidgetWeather : ProSummaryWidget() {
    override val layout = R.layout.widget_pro_weather
    override val openRoute = "brief"
    override val requestCode = 202

    override fun bind(context: Context, skin: Skin, views: RemoteViews) {
        val line = ProHomeWidgets.weatherLine(context)
        val head = when {
            " — " in line -> line.substringBefore(" — ").trim()
            ", " in line && line.firstOrNull()?.let { it == '+' || it == '-' || it.isDigit() } == true ->
                line.substringBefore(", ").trim()
            else -> line.substringBefore(" — ").ifBlank { line }.trim()
        }.ifBlank { "—" }
        val sub = when {
            " — " in line -> line.substringAfter(" — ").trim()
            head != line && line.contains(", ") -> line.substringAfter(", ").trim()
            else -> "Нажми ↻ чтобы обновить"
        }.ifBlank { "Нажми ↻ чтобы обновить" }
        views.setTextViewText(R.id.wTitle, "ПОГОДА")
        views.setTextViewText(R.id.wBig, head)
        views.setTextViewText(R.id.wSub, sub)
        views.setTextColor(R.id.wTitle, skin.accent)
        views.setTextColor(R.id.wBig, skin.text)
        views.setTextColor(R.id.wSub, skin.muted)
        views.setTextViewText(R.id.wRefresh, "↻")
        views.setTextColor(R.id.wRefresh, skin.accent)
        views.setOnClickPendingIntent(
            R.id.wRefresh,
            ProHomeWidgets.weatherPending(context, javaClass, 270),
        )
    }
}

class JarvisWidgetBattery : ProSummaryWidget() {
    override val layout = R.layout.widget_pro_battery
    override val openRoute = "system"
    override val requestCode = 203

    override fun bind(context: Context, skin: Skin, views: RemoteViews) {
        val pct = ProHomeWidgets.batteryPct(context)
        val charge = ProHomeWidgets.charging(context)
        views.setTextViewText(R.id.wTitle, "ЗАРЯД")
        views.setTextViewText(R.id.wBig, "$pct%")
        views.setTextViewText(
            R.id.wSub,
            when {
                charge -> "Заряжается"
                pct < 30 -> "Стоит подзарядить"
                else -> "Хватит на день"
            },
        )
        views.setTextColor(R.id.wTitle, skin.accent)
        views.setTextColor(R.id.wBig, if (pct < 30 && !charge) skin.danger else skin.text)
        views.setTextColor(R.id.wSub, skin.muted)
    }
}

class JarvisWidgetNext : ProSummaryWidget() {
    override val layout = R.layout.widget_pro_next
    override val openRoute = "plans"
    override val requestCode = 204

    override fun bind(context: Context, skin: Skin, views: RemoteViews) {
        val (title, sub) = ProHomeWidgets.nextTask(context)
        views.setTextViewText(R.id.wBig, title)
        views.setTextViewText(R.id.wSub, sub)
        views.setTextColor(R.id.wBig, skin.text)
        views.setTextColor(R.id.wSub, skin.muted)
        views.setTextViewText(R.id.wBadge, "J")
        views.setTextColor(R.id.wBadge, skin.onAccent)
        runCatching { views.setInt(R.id.wBadge, "setBackgroundResource", R.drawable.bg_widget_pro_badge) }
    }
}

class JarvisWidgetTasks : ProSummaryWidget() {
    override val layout = R.layout.widget_pro_tasks
    override val openRoute = "plans"
    override val requestCode = 205

    override fun bind(context: Context, skin: Skin, views: RemoteViews) {
        val list = ProHomeWidgets.tasks(context)
        views.setTextViewText(R.id.wTitle, "ДЖАРВИС · ДЕЛА")
        views.setTextViewText(R.id.wHint, if (list.isEmpty()) "0" else "${list.size}")
        views.setTextColor(R.id.wTitle, skin.accent)
        views.setTextColor(R.id.wHint, skin.muted)
        listOf(R.id.wLine1, R.id.wLine2, R.id.wLine3).forEachIndexed { i, id ->
            if (i < list.size) {
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, "· ${list[i]}")
                views.setTextColor(id, skin.text)
            } else views.setViewVisibility(id, View.GONE)
        }
        if (list.isEmpty()) {
            views.setViewVisibility(R.id.wLine1, View.VISIBLE)
            views.setTextViewText(R.id.wLine1, "Скажи «Джарвис, напомни…»")
            views.setTextColor(R.id.wLine1, skin.muted)
        }
    }
}

class JarvisWidgetMusic : ProSummaryWidget() {
    override val layout = R.layout.widget_pro_music
    override val openRoute = "dialog"
    override val requestCode = 206

    override fun bind(context: Context, skin: Skin, views: RemoteViews) {
        val now = runCatching { ProHomeWidgets.musicNow(context) }
            .getOrDefault(ProHomeWidgets.MusicNow("Музыка", "Управление плеером", false))
        views.setTextViewText(R.id.wTitle, "МУЗЫКА")
        views.setTextViewText(R.id.wBig, now.title)
        views.setTextViewText(R.id.wSub, now.sub)
        views.setTextColor(R.id.wTitle, skin.accent)
        views.setTextColor(R.id.wBig, skin.text)
        views.setTextColor(R.id.wSub, skin.muted)
        views.setTextViewText(R.id.wPrev, "⏮")
        views.setTextViewText(R.id.wPlay, if (now.playing) "❚❚" else "▶")
        views.setTextViewText(R.id.wNext, "⏭")
        views.setTextColor(R.id.wPrev, skin.text)
        views.setTextColor(R.id.wPlay, skin.onAccent)
        views.setTextColor(R.id.wNext, skin.text)
        runCatching { views.setInt(R.id.wPrev, "setBackgroundResource", R.drawable.bg_widget_pro_chip) }
        runCatching { views.setInt(R.id.wPlay, "setBackgroundResource", ProHomeWidgets.skinBtn(context)) }
        runCatching { views.setInt(R.id.wNext, "setBackgroundResource", R.drawable.bg_widget_pro_chip) }
        views.setOnClickPendingIntent(
            R.id.wPrev,
            ProHomeWidgets.musicPending(context, javaClass, "prev", 261),
        )
        views.setOnClickPendingIntent(
            R.id.wPlay,
            ProHomeWidgets.musicPending(context, javaClass, "toggle", 262),
        )
        views.setOnClickPendingIntent(
            R.id.wNext,
            ProHomeWidgets.musicPending(context, javaClass, "next", 263),
        )
    }
}
