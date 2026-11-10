package com.jarvis.app.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.jarvis.app.ActivityFeed
import com.jarvis.app.JarvisSettings
import com.jarvis.app.JarvisWidgetProvider
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.model.AssistantState
import com.jarvis.tools.WeatherTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Погода для главной: «+6°» и короткое описание, или null без сети и геолокации. */
internal suspend fun fetchWeather(host: JarvisActivity): Pair<String, String>? {
    val message = withContext(Dispatchers.IO) {
        runCatching { WeatherTool(host).execute(ToolCall(requestId = "ui", toolId = "weather", arguments = emptyMap())).message }.getOrNull()
    }.orEmpty()
    val degrees = Regex("сейчас (-?\\d+)°").find(message)?.groupValues?.get(1) ?: return null
    val temp = (if (degrees.startsWith("-")) "" else "+") + "$degrees°"
    val sub = message.split(". ").getOrNull(1).orEmpty()
    JarvisSettings.prefs(host).edit().putString(JarvisWidgetProvider.PREF_WEATHER, "$temp, ${sub.lowercase()}").apply()
    JarvisWidgetProvider.refresh(host)
    return temp to sub
}

/** Главная премиум-стиля: ядро из частиц на шкале-приборе, живые данные по углам и лента событий. */
class PremiumHomeScreen(host: JarvisActivity) : Screen(host) {
    private val core = ParticleCoreView(host, skin)
    private val dial = DialView(host, skin)
    private val pill = host.monoLabel("", 10f, skin.text)
    private val pillDot = View(host)
    private val greet = host.label("", 25f, skin.text, Fonts.display(host, 600))
    private val dateLine = host.monoLabel("", 10.5f, skin.muted)
    private val stateEn = host.monoLabel("", 10f, skin.accent).apply { letterSpacing = .32f; gravity = Gravity.CENTER }
    private val stateRu = host.label("", 15f, skin.text, Fonts.body(host, 500), Gravity.CENTER, lines = 2)
    private val log = host.column(2)
    private val tl = readout(Gravity.START)
    private val tr = readout(Gravity.END)
    private val bl = readout(Gravity.START)
    private val br = readout(Gravity.END)
    private var hintOverride: String? = null
    private var weather: Pair<String, String>? = null
    private var weatherAt = 0L
    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            if (tick % VITALS_EVERY == 0) vitals()
            if (host.assistantState == AssistantState.LISTENING) stateRu.text = stateText()
            view.postDelayed(this, TICK_MS)
        }
    }

    override val view: View = host.column().apply {
        addView(top())
        addView(host.column().pad(22, 0).apply {
            setPadding(paddingLeft, host.dp(14), paddingRight, 0)
            addView(greet)
            addView(dateLine, lp().apply { topMargin = host.dp(6) })
        })
        addView(stage(), lp(h = 0, weight = 1f))
        addView(host.column(4).pad(20, 0).apply { addView(stateEn); addView(stateRu) })
        addView(log.apply {
            background = host.glassBg(skin, 18)
            setPadding(host.dp(14), host.dp(9), host.dp(14), host.dp(9))
            minimumHeight = host.dp(62)
            tap { host.show(JarvisActivity.CHAT) }
        }, lp().apply { setMargins(host.dp(14), host.dp(10), host.dp(14), 0) })
        addView(quick(), lp().apply { setMargins(host.dp(14), host.dp(10), host.dp(14), host.dp(30)) })
    }

    private fun readout(g: Int) = host.label("", 9.5f, skin.muted, Fonts.mono(host), g).apply { letterSpacing = .04f; setLineSpacing(0f, 1.35f) }

    private fun top(): View {
        val row = host.rowLayout(10).pad(20, 0).apply { setPadding(paddingLeft, host.dp(14), paddingRight, 0) }
        val logo = SpannableStringBuilder("JARVIS")
        row.addView(host.label(logo, 15f, skin.text, Fonts.logo(host)).apply {
            letterSpacing = .06f
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, host.dp(18).toFloat(),
                intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.parseColor("#C9CFD9"), android.graphics.Color.parseColor("#7D8592"), android.graphics.Color.parseColor("#E8ECF2")),
                floatArrayOf(0f, .45f, .55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        })
        row.addView(host.monoLabel("v0.1", 9f, skin.accent).apply { setPadding(0, 0, 0, host.dp(8)) })
        row.addView(View(host), lp(0, 1, 1f))
        val p = host.rowLayout(7).pad(11, 6).apply {
            background = host.glassBg(skin, 20)
            addView(pillDot, lp(host.dp(6), host.dp(6)))
            addView(pill)
            tap { host.show(JarvisActivity.STATUS) }
        }
        row.addView(p)
        return row
    }

    private fun stage(): View {
        val stage = FrameLayout(host)
        stage.addView(dial, FrameLayout.LayoutParams(-1, -1))
        stage.addView(core, FrameLayout.LayoutParams(-1, -1))
        stage.addView(View(host).apply {
            contentDescription = "Включить или выключить ассистента"
            tap { if (host.isOn) host.powerOff() else host.powerOn() }
        }, FrameLayout.LayoutParams(host.dp(220), host.dp(220), Gravity.CENTER))
        val m = host.dp(16)
        stage.addView(tl, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(m, host.dp(14), 0, 0) })
        stage.addView(tr.apply { tap { host.show(JarvisActivity.STATUS) } }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, host.dp(14), m, 0) })
        stage.addView(bl.apply { tap { host.show(JarvisActivity.SCAN) } }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m, 0, 0, host.dp(6)) })
        stage.addView(br.apply { tap { host.openLive() } }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, m, host.dp(6)) })
        return stage
    }

    private fun quick(): View {
        val row = host.rowLayout(8)
        fun tile(n: String, icon: String, title: String, id: String) {
            val box = host.column(8).pad(9, 10).apply {
                background = host.glassBg(skin, 18)
                addView(host.monoLabel(n, 9f, skin.metal))
                addView(IconView(host, icon, skin.text, 20))
                addView(host.label(title, 11f, skin.text, Fonts.body(host, 600), lines = 2))
                tap { host.show(id) }
            }
            row.addView(box, lp(0, -1, 1f))
        }
        tile("01", Ic.BOLT, "Быстрые действия", JarvisActivity.SKILLS)
        tile("02", Ic.CLOCK, "Рутины", JarvisActivity.ROUTINES)
        tile("03", Ic.CHART, "Статус системы", JarvisActivity.STATUS)
        tile("04", Ic.GLOBE, "Анализ ситуации", JarvisActivity.SCAN)
        return row
    }

    override fun hint(text: String) {
        hintOverride = text
        render()
    }

    override fun onShow() {
        view.removeCallbacks(ticker)
        view.post(ticker)
        core.invalidate()
        dial.invalidate()
        render()
        loadWeather()
    }

    override fun onHide() = view.removeCallbacks(ticker).let { }

    override fun onState(state: AssistantState) {
        hintOverride = null
        render()
    }

    override fun onData() = render()

    private fun loadWeather() {
        val now = System.currentTimeMillis()
        if (now - weatherAt < WEATHER_TTL_MS) return
        weatherAt = now
        host.lifecycleScope.launch {
            weather = fetchWeather(host)
            if (weather == null) weatherAt = 0
            render()
        }
    }

    /** Показатели по углам: только настоящие цифры телефона. */
    private fun vitals() {
        tl.text = rows("СЕТЬ" to Device.network(host), "ЗАРЯД" to "${Device.battery(host)}%", "ТЕМП." to "${Device.temperature(host)}°")
        tr.text = rows("CPU" to "${Device.cpuPercent()}%", "RAM" to "${Device.ramPercent(host)}%", "ДИСК" to "${Device.freeStorageGb()} ГБ")
        bl.text = weather?.let { rows("ПОГОДА" to it.first) } ?: rows("ПОГОДА" to "—")
        br.text = rows("LIVE" to "БЕЗ СЛОВА", "WAKE" to "«ДЖАРВИС»")
    }

    private fun rows(vararg items: Pair<String, String>): CharSequence {
        val b = SpannableStringBuilder()
        items.forEachIndexed { i, (k, v) ->
            if (i > 0) b.append('\n')
            b.append("$k ")
            val s = b.length
            b.append(v)
            b.setSpan(ForegroundColorSpan(skin.text), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    private fun stateText(): CharSequence {
        val state = host.assistantState
        hintOverride?.let { return it }
        val text = when (state) {
            AssistantState.OFFLINE -> "Нажмите на ядро, чтобы включить"
            AssistantState.ERROR -> "Сбой. Нажмите на ядро, чтобы перезапустить"
            AssistantState.STARTING -> "Загружаю голос и модели"
            AssistantState.LISTENING -> host.lastPartial.takeIf { it.isNotBlank() }?.let { "«$it" } ?: "Скажите, что нужно сделать"
            AssistantState.THINKING -> STEPS.getOrElse(host.answerStep) { STEPS[1] }
            AssistantState.EXECUTING -> "Выполняю действие"
            AssistantState.SPEAKING -> host.lastResponse.ifBlank { "Говорю" }
            else -> "Скажите «Джарвис» или нажмите на микрофон"
        }
        if (state != AssistantState.LISTENING) return text
        // Мигающий курсор, пока он слушает.
        return if (tick % 2 == 0) "$text▍" else "$text "
    }

    private fun render() {
        val state = host.assistantState
        val on = host.isOn
        core.mode = when (state) {
            AssistantState.OFFLINE, AssistantState.ERROR -> ParticleCoreView.Mode.OFF
            AssistantState.LISTENING -> ParticleCoreView.Mode.LISTEN
            AssistantState.THINKING, AssistantState.EXECUTING -> ParticleCoreView.Mode.THINK
            AssistantState.SPEAKING -> ParticleCoreView.Mode.SPEAK
            else -> ParticleCoreView.Mode.IDLE
        }
        val tone = stateTone(skin, state)
        dial.tone = tone
        greet.text = "${Device.greeting()}, сэр."
        val alarm = Device.nextAlarm(host)?.let { " · БУДИЛЬНИК " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }.orEmpty()
        val wx = weather?.let { " · ${it.first} ${it.second}" }.orEmpty()
        dateLine.text = SimpleDateFormat("EE d MMM", Locale("ru")).format(Date()).replace(".", "") + wx + alarm
        stateEn.text = when (state) {
            AssistantState.OFFLINE -> "OFFLINE"
            AssistantState.ERROR -> "ERROR"
            AssistantState.STARTING -> "BOOTING"
            AssistantState.LISTENING -> "LISTENING"
            AssistantState.THINKING -> "THINKING"
            AssistantState.EXECUTING -> "EXECUTING"
            AssistantState.SPEAKING -> "SPEAKING"
            else -> "STANDBY"
        }
        stateEn.setTextColor(tone)
        stateEn.setShadowLayer(18f, 0f, 0f, tone)
        stateRu.text = stateText()
        pill.text = when {
            !on -> "ВЫКЛЮЧЕН"
            state == AssistantState.STARTING -> "ЗАПУСК"
            else -> "ОНЛАЙН · СЛЫШУ"
        }
        pillDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) tone else skin.metal)
        }
        renderLog()
        vitals()
    }

    private fun renderLog() {
        log.removeAllViews()
        val items = ActivityFeed.all().take(3).reversed()
        if (items.isEmpty()) {
            log.addView(host.label("Здесь появится, что делает Джарвис: запросы, вызовы и ответы.", 10.5f, skin.muted, Fonts.mono(host), lines = 2))
            return
        }
        items.forEach { item ->
            val b = SpannableStringBuilder(ActivityFeed.formatTime(item.atMs)).append("  ")
            val s = b.length
            b.append(item.title)
            b.setSpan(ForegroundColorSpan(skin.accent), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append("  ")
            val d = b.length
            b.append(item.detail)
            b.setSpan(ForegroundColorSpan(skin.text), d, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            log.addView(host.label(b, 10f, skin.muted, Fonts.mono(host), lines = 1))
        }
    }

    private companion object {
        val STEPS = listOf("Распознаю речь", "Смотрю контекст", "Ищу сведения", "Формирую ответ", "Готовлю голос")
        const val WEATHER_TTL_MS = 30 * 60_000L
        const val TICK_MS = 600L
        const val VITALS_EVERY = 10
    }
}

/** Цвет состояния: «думаю» фиолетовый, выключен серый, остальное — свет Джарвиса. */
internal fun stateTone(skin: Skin, state: AssistantState): Int = when (state) {
    AssistantState.THINKING, AssistantState.EXECUTING -> skin.violet
    AssistantState.OFFLINE, AssistantState.ERROR -> if (skin.premium) mix(skin.metal, skin.accent, .3f) else skin.metal
    else -> skin.accent
}
