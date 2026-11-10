package com.jarvis.app.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.jarvis.app.JarvisSettings
import com.jarvis.app.JarvisWidgetProvider
import com.jarvis.app.VoiceController
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.model.AssistantState
import com.jarvis.tools.WeatherTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeScreen(host: JarvisActivity) : Screen(host) {
    private val reactor = ReactorView(host, host.skin)
    private val wave = WaveView(host, host.skin)
    private val statusDot = View(host)
    private val status = host.label("", 12f, skin.good, lines = 1)
    private val clock = host.label("", 22f, skin.text, Fonts.display(host, 500))
    private val bubble = host.label("", 14f, skin.text, Fonts.body(host, 600), lines = 4)
    private val word = host.label("", 16f, skin.text, Fonts.body(host, 600), lines = 1)
    private val hintText = host.label("", 12f, skin.muted, lines = 2).apply { minLines = 2 }
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = Device.time()
            vitals.text = Device.vitals(host)
            view.postDelayed(this, CLOCK_TICK_MS)
        }
    }
    private val vitals = host.label("", 11f, skin.muted, lines = 3)
    private val weather = host.label("—", 20f, skin.text, Fonts.display(host))
    private val weatherSub = host.label("", 11.5f, skin.muted, lines = 1)
    private var wasOn = false
    private var hintOverride: String? = null
    private var weatherAt = 0L
    private var greeted = false

    override val view: View = host.column().apply {
        addView(topBar())
        addView(stage(), lp(h = 0, weight = 1f))
        addView(voicePanel(), lp().apply { setMargins(host.dp(14), host.dp(6), host.dp(14), 0) })
        addView(tiles(), lp().apply { setMargins(host.dp(14), host.dp(8), host.dp(14), host.dp(30)) })
    }

    private fun topBar(): View {
        val row = host.rowLayout(10).pad(14, 10)
        val left = host.column()
        left.addView(host.label("J.A.R.V.I.S.", 20f, skin.text, Fonts.brand(host)).apply { letterSpacing = .14f; setShadowLayer(16f, 0f, 0f, skin.accent) })
        val st = host.rowLayout(6).apply { setPadding(0, host.dp(3), 0, 0) }
        st.addView(statusDot, lp(host.dp(7), host.dp(7)))
        st.addView(status)
        left.addView(st)
        row.addView(left, lp(0, -2, 1f))
        row.addView(clock)
        return row
    }


    private fun stage(): View {
        val stage = FrameLayout(host)
        stage.addView(reactor, FrameLayout.LayoutParams(-1, -1))
        // Нажатие на центр ядра включает и выключает ассистента целиком.
        stage.addView(View(host).apply {
            contentDescription = "Включить или выключить ассистента"
            tap { if (host.isOn) host.powerOff() else host.powerOn() }
        }, FrameLayout.LayoutParams(host.dp(220), host.dp(220), Gravity.CENTER))
        val m = host.dp(14)
        stage.addView(bubble.apply {
            background = host.glassBg(skin, 12)
            setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7))
            maxWidth = host.dp(190)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(m, host.dp(4), 0, 0) })
        stage.addView(vitals.apply {
            background = host.glassBg(skin, 12)
            setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7))
            tap { host.show(JarvisActivity.STATUS) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, host.dp(4), m, 0) })
        val wx = host.rowLayout(8).pad(10, 7).apply {
            background = host.glassBg(skin, 12)
            addView(weather)
            addView(weatherSub)
            tap { host.show(JarvisActivity.SCAN) }
        }
        stage.addView(wx, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, m, host.dp(4)) })
        val liveChip = host.rowLayout(8).pad(10, 7).apply {
            background = host.glassBg(skin, 12)
            addView(IconView(host, Ic.LIVE, skin.accent, 20))
            addView(host.column().apply {
                addView(host.label("Режим Live", 12f, skin.text))
                addView(host.label("Без слова", 11f, skin.good))
            })
            tap { host.openLive() }
        }
        stage.addView(liveChip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m, 0, 0, host.dp(4)) })
        return stage
    }

    private fun voicePanel(): View {
        val row = host.rowLayout(12).pad(12, 10)
        row.background = host.glassBg(skin)
        row.minimumHeight = host.dp(66)
        val t = host.column()
        t.addView(word)
        t.addView(hintText)
        row.addView(t, lp(0, -2, 1.3f))
        row.addView(wave, lp(0, host.dp(40), 1f))
        row.tap { host.show(JarvisActivity.CHAT) }
        return row
    }

    private fun tiles(): View {
        val row = host.rowLayout(8)
        fun tile(icon: String, title: String, id: String) {
            val box = host.column().pad(2, 9).apply {
                gravity = Gravity.CENTER
                background = host.glassBg(skin)
                addView(IconView(host, icon, skin.accent))
                addView(host.label(title, 11f, skin.text, gravity = Gravity.CENTER).apply { setPadding(0, host.dp(5), 0, 0) })
                tap { host.show(id) }
            }
            row.addView(box, lp(0, -1, 1f))
        }
        tile(Ic.BOLT, "Быстрые\nдействия", JarvisActivity.SKILLS)
        tile(Ic.CLOCK, "Рутины", JarvisActivity.ROUTINES)
        tile(Ic.CHART, "Статус\nсистемы", JarvisActivity.STATUS)
        tile(Ic.GLOBE, "Анализ\nситуации", JarvisActivity.SCAN)
        return row
    }

    override fun hint(text: String) {
        hintOverride = text
        render()
    }

    override fun onShow() {
        view.removeCallbacks(clockTick)
        view.post(clockTick)
        render()
        loadWeather()
    }

    override fun onHide() = view.removeCallbacks(clockTick).let { }

    private fun loadWeather() {
        val now = System.currentTimeMillis()
        if (now - weatherAt < WEATHER_TTL_MS) return
        weatherAt = now
        host.lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { WeatherTool(host).execute(ToolCall(requestId = "ui", toolId = "weather", arguments = emptyMap())).message }.getOrNull()
            }.orEmpty()
            val degrees = Regex("сейчас (-?\\d+)°").find(message)?.groupValues?.get(1)
            if (degrees == null) { weatherAt = 0; return@launch }
            weather.text = (if (degrees.startsWith("-")) "" else "+") + "$degrees°"
            weatherSub.text = message.split(". ").getOrNull(1).orEmpty()
            JarvisSettings.prefs(host).edit().putString(JarvisWidgetProvider.PREF_WEATHER, "${weather.text}, ${weatherSub.text.toString().lowercase()}").apply()
            JarvisWidgetProvider.refresh(host)
        }
    }

    override fun onState(state: AssistantState) {
        hintOverride = null
        render()
    }

    override fun onData() = render()

    private fun render() {
        val state = host.assistantState
        val on = host.isOn
        if (on && !wasOn) reactor.kick()
        wasOn = on
        reactor.mode = when (state) {
            AssistantState.OFFLINE, AssistantState.ERROR -> ReactorView.Mode.OFF
            AssistantState.LISTENING -> ReactorView.Mode.LISTEN
            AssistantState.THINKING -> ReactorView.Mode.THINK
            AssistantState.EXECUTING -> ReactorView.Mode.ACT
            AssistantState.SPEAKING -> ReactorView.Mode.SPEAK
            else -> ReactorView.Mode.IDLE
        }
        reactor.step = host.answerStep
        wave.level = when (state) {
            AssistantState.LISTENING -> .6f
            AssistantState.SPEAKING -> .85f
            AssistantState.THINKING, AssistantState.EXECUTING -> .4f
            AssistantState.OFFLINE, AssistantState.ERROR -> 0f
            else -> .08f
        }
        word.text = when (state) {
            AssistantState.OFFLINE -> "Выключен"
            AssistantState.ERROR -> "Сбой"
            AssistantState.STARTING -> "Запускаюсь"
            AssistantState.LISTENING -> "Слушаю…"
            AssistantState.THINKING -> "Думаю…"
            AssistantState.EXECUTING -> "Выполняю"
            AssistantState.SPEAKING -> "Говорю"
            else -> "Ожидаю"
        }
        hintText.text = hintOverride ?: when (state) {
            AssistantState.OFFLINE -> "Нажмите на ядро, чтобы включить"
            AssistantState.ERROR -> "Нажмите на ядро, чтобы перезапустить"
            AssistantState.STARTING -> "Загружаю голос и модели"
            AssistantState.LISTENING -> host.lastPartial.ifBlank { "Скажите, что нужно сделать" }
            AssistantState.THINKING -> STEPS.getOrElse(host.answerStep) { STEPS[1] }
            AssistantState.EXECUTING -> "Выполняю действие"
            AssistantState.SPEAKING -> "Скажите «Джарвис», чтобы перебить"
            else -> "Скажите «Джарвис» или нажмите на микрофон"
        }
        status.text = when {
            !on -> "Выключен"
            state == AssistantState.STARTING -> "Запускаюсь"
            else -> "Онлайн · слышу «Джарвис»"
        }
        status.setTextColor(if (on) skin.good else skin.muted)
        statusDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (on) skin.good else skin.muted)
        }
        bubble.text = host.lastResponse.ifBlank {
            if (host.lastRequest.isNotBlank()) "«${host.lastRequest}»"
            else if (on) "${Device.greeting()}, сэр. Чем займёмся?" else "Ассистент выключен, сэр."
        }
    }

    private companion object {
        val STEPS = listOf("Распознаю речь", "Смотрю контекст", "Ищу сведения", "Формирую ответ", "Готовлю голос")
        const val WEATHER_TTL_MS = 30 * 60_000L
        const val CLOCK_TICK_MS = 15_000L
    }
}

/** Live: отдельный режим разговора без слова активации, запускается только своей кнопкой. */
class LiveScreen(host: JarvisActivity) : Screen(host) {
    /** Сцена Live тёмная в любой теме, поэтому светлой теме нужны светлые надписи. */
    override val skin: Skin = darkened(host.skin)
    val stageColor: Int = if (skin.premium) skin.bg else mix(android.graphics.Color.BLACK, skin.violet, .06f)
    private val mind = MindView(host, skin)
    /** Премиум: в Live то же ядро из частиц, только крупнее. */
    private val core = if (skin.premium) ParticleCoreView(host, skin, 3000).apply { scale = .34f; mode = ParticleCoreView.Mode.OFF } else null
    private val stat = host.label("", 12f, skin.muted)
    private val time = host.label("", 16f, skin.muted, Fonts.display(host))
    private val you = host.label("", 14f, skin.muted, lines = 3)
    private val him = host.label("", 18f, skin.text, Fonts.body(host, 500), lines = 5)
    private val toggle = TextView(host)
    private val muteBtn = FrameLayout(host)
    private val muteIcon = IconView(host, Ic.MIC, skin.text)
    private val chips = listOf("Слушаю", "Думаю", "Говорю").map { host.label(it, 11f, skin.muted, gravity = Gravity.CENTER) }
    private val chipBox = host.column(6)
    private var talking = false
    private var pendingStart = false
    private val ticker = object : Runnable {
        override fun run() {
            if (talking) {
                val s = (System.currentTimeMillis() - VoiceController.liveStartedAt) / 1000
                time.text = "%02d:%02d".format(s / 60, s % 60)
                view.postDelayed(this, 1000)
            }
        }
    }

    override val view: View = build().let { col ->
        if (!skin.premium) col else FrameLayout(host).apply {
            setBackgroundColor(stageColor)
            isClickable = true
            addView(SmokeView(host, skin), FrameLayout.LayoutParams(-1, -1))
            addView(col, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun build(): View {
        val root = host.column().apply {
            if (!skin.premium) setBackgroundColor(stageColor)
            isClickable = true
        }
        val bar = host.rowLayout(8).pad(14, 10)
        bar.addView(FrameLayout(host).apply {
            addView(IconView(host, Ic.BACK, skin.accent), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            contentDescription = "Назад"
            tap { host.closeLive() }
        }, lp(host.dp(42), host.dp(42)))
        val title = host.column()
        if (skin.premium) {
            title.addView(host.monoLabel("LIVE · БЕЗ СЛОВА-АКТИВАТОРА", 10f, skin.metal))
            title.addView(host.label("Разговор", 27f, skin.text, Fonts.display(host, 600)))
        } else title.addView(host.label("LIVE", 15f, skin.text, Fonts.display(host)).apply { letterSpacing = .1f })
        title.addView(stat)
        bar.addView(title, lp(0, -2, 1f))
        bar.addView(time)
        root.addView(bar)
        val stage = FrameLayout(host)
        stage.addView(core ?: mind, FrameLayout.LayoutParams(-1, -1))
        chips.forEach { c -> c.setPadding(host.dp(9), host.dp(6), host.dp(9), host.dp(6)); c.background = host.glassBg(skin, 10); chipBox.addView(c, lp(host.dp(84), -2)) }
        stage.addView(chipBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, host.dp(8), host.dp(10), 0) })
        root.addView(stage, lp(h = 0, weight = 1f))
        val text = host.column(4).pad(20, 0).apply { minimumHeight = host.dp(96) }
        text.addView(you)
        text.addView(him)
        root.addView(text)
        val ctl = host.rowLayout(20).pad(16, 10).apply { gravity = Gravity.CENTER }
        muteBtn.apply {
            background = host.glassBg(skin, 26)
            addView(muteIcon, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            contentDescription = "Выключить микрофон"
            tap { toggleMute() }
        }
        ctl.addView(muteBtn, lp(host.dp(52), host.dp(52)))
        toggle.apply {
            textSize = 15f
            typeface = Fonts.body(host, 700)
            gravity = Gravity.CENTER
            setPadding(host.dp(26), 0, host.dp(26), 0)
            tap { if (talking) stopTalk(true) else startTalk() }
        }
        ctl.addView(toggle, lp(-2, host.dp(52)))
        root.addView(ctl)
        return root
    }

    fun startTalk() {
        if (!host.isOn) host.powerOn()
        pendingStart = !VoiceController.isRunning
        talking = true
        VoiceController.liveStartedAt = System.currentTimeMillis()
        VoiceController.liveTalk = true
        VoiceController.liveMuted = false
        him.text = "Я здесь, сэр. Говорите, когда будете готовы."
        you.text = ""
        ui()
        view.post(ticker)
    }

    private fun stopTalk(said: Boolean) {
        talking = false
        VoiceController.liveTalk = false
        VoiceController.liveMuted = false
        VoiceController.stopSpeaking()
        if (said) him.text = "Разговор завершён, сэр."
        you.text = ""
        ui()
    }

    private fun toggleMute() {
        VoiceController.liveMuted = !VoiceController.liveMuted
        muteIcon.tint = if (VoiceController.liveMuted) skin.warn else skin.text
        muteIcon.invalidate()
    }

    private fun ui() {
        toggle.text = if (talking) "Завершить" else "Начать разговор"
        toggle.setTextColor(if (talking) skin.danger else (if (skin.light) skin.onAccent else skin.text))
        if (skin.premium) {
            // Премиум: «Завершить» — красная кнопка, «Начать» — серебряная.
            toggle.setTextColor(if (talking) android.graphics.Color.WHITE else skin.onAccent)
            toggle.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                if (talking) intArrayOf(android.graphics.Color.parseColor("#FF6B5E"), android.graphics.Color.parseColor("#B8281F"))
                else intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.parseColor("#D5DBE4")),
            ).apply { cornerRadius = host.dp(26).toFloat() }
        } else toggle.background = host.glassBg(skin, 26, if (talking) android.graphics.Color.TRANSPARENT else alpha(skin.accent, if (skin.light) 1f else .3f), if (talking) skin.danger else skin.accent)
        stat.text = if (talking) "Идёт разговор, слово активации не нужно" else "Разговор выключен"
        stat.setTextColor(if (talking) skin.good else skin.muted)
        muteBtn.visibility = if (talking) View.VISIBLE else View.GONE
        chipBox.visibility = if (talking) View.VISIBLE else View.GONE
        if (!talking) {
            time.text = ""
            mind.mood = MindView.Mood.SLEEP
            core?.mode = ParticleCoreView.Mode.OFF
        } else onState(host.assistantState)
    }

    override fun onShow() {
        talking = VoiceController.liveTalk
        him.text = "Нажмите «Начать разговор», чтобы говорить без слова активации, сэр."
        ui()
        if (talking) { view.removeCallbacks(ticker); view.post(ticker); onData() }
    }

    /** Разговор могли завершить в орбе, пока окно было свёрнуто. */
    fun sync() {
        if (talking == VoiceController.liveTalk) return
        talking = VoiceController.liveTalk
        if (!talking) him.text = "Разговор завершён, сэр."
        ui()
        if (talking) { view.removeCallbacks(ticker); view.post(ticker) }
    }

    /** Уход с экрана завершает разговор: Live не должен слушать незаметно. */
    override fun onHide() {
        if (talking) stopTalk(false)
    }

    override fun onState(state: AssistantState) {
        if (!talking) return
        val index = when (state) {
            AssistantState.THINKING, AssistantState.EXECUTING -> 1
            AssistantState.SPEAKING -> 2
            else -> 0
        }
        mind.mood = listOf(MindView.Mood.LISTEN, MindView.Mood.THINK, MindView.Mood.SPEAK)[index]
        core?.mode = listOf(ParticleCoreView.Mode.LISTEN, ParticleCoreView.Mode.THINK, ParticleCoreView.Mode.SPEAK)[index]
        chips.forEachIndexed { i, c ->
            c.setTextColor(if (i == index) skin.text else skin.muted)
            c.background = host.glassBg(skin, 10, skin.glass, if (i == index) skin.violet else skin.line)
        }
    }

    override fun onData() {
        if (!talking) return
        you.text = host.lastPartial.ifBlank { host.lastRequest }.takeIf { it.isNotBlank() }?.let { "«$it»" }.orEmpty()
        if (host.lastResponse.isNotBlank()) him.text = host.lastResponse
    }
}

/** Тёмный вариант темы для сцены Live: акцентные цвета свои, текст и стекло как у тёмных тем. */
internal fun darkened(skin: Skin): Skin = if (!skin.light) skin else skin.copy(
    bg = android.graphics.Color.parseColor("#05070D"),
    glass = android.graphics.Color.parseColor("#8F0B1C34"),
    line = android.graphics.Color.parseColor("#524696FF"),
    text = android.graphics.Color.parseColor("#ECF4FF"),
    muted = android.graphics.Color.parseColor("#8FA8C6"),
    onAccent = android.graphics.Color.parseColor("#FFFFFF"),
    light = false,
)
