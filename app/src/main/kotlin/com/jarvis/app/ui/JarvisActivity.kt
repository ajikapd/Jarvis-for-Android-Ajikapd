package com.jarvis.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.app.ActivityFeed
import com.jarvis.app.ConversationStore
import com.jarvis.app.JarvisSettings
import com.jarvis.app.OnboardingActivity
import com.jarvis.app.OverlayOrbService
import com.jarvis.app.UiMode
import com.jarvis.app.NativeGuard
import com.jarvis.app.CrashReports
import com.jarvis.app.DashboardActivity
import com.jarvis.core.startup.StartupRoute
import com.jarvis.app.VoiceController
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Окно нового интерфейса: панель, экраны и связь с голосовым сервисом. */
class JarvisActivity : AppCompatActivity() {
    lateinit var skin: Skin
        private set

    var assistantState = AssistantState.OFFLINE
        private set
    /** Шаг ответа по настоящим событиям конвейера, -1 вне запроса. */
    var answerStep = -1
        private set
    var lastRequest = ""
        private set
    var lastResponse = ""
        private set
    var lastPartial = ""
        private set

    val isOn: Boolean get() = assistantState != AssistantState.OFFLINE && assistantState != AssistantState.ERROR

    private lateinit var content: FrameLayout
    private lateinit var navHost: View
    private lateinit var liveHost: FrameLayout
    private lateinit var sheetHost: FrameLayout
    private lateinit var mic: FrameLayout
    private var smoke: SmokeView? = null
    private val tabs = LinkedHashMap<String, Pair<IconView, TextView>>()
    private val screens = HashMap<String, Screen>()
    private var current = ""
    private var previous = HOME
    private var live: LiveScreen? = null
    private var sectionOnly = false
    private var pendingLive = false
    private var confirmJob: Job? = null
    private var askingYesNo = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askNotificationsThenStart()
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        VoiceController.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Окно одного раздела (например, запись голоса из гида): без шагов первого запуска и без переадресации.
        sectionOnly = intent.getBooleanExtra(UiMode.EXTRA_FORCE_MODERN, false) && !OnboardingActivity.needsOnboarding(this)
        if (!sectionOnly) {
            val route = UiMode.route(this)
            UiMode.stepIntent(this, route)?.let {
                startActivity(it)
                finish()
                return
            }
            if (route == StartupRoute.CLASSIC) {
                // Классический интерфейс выбран: передаём ему тот же запуск (виджет, ассистент, ярлык).
                // Без флагов и категории лаунчера: с ними Android лишь поднимает эту же задачу,
                // классический экран не создаётся, и после finish() приложение «вылетает» на рабочий стол.
                startActivity(classicIntent(intent))
                finish()
                return
            }
        }
        skin = Skins.current(this)
        Fonts.premium = skin.premium
        window.statusBarColor = skin.bg
        window.navigationBarColor = skin.bg
        window.decorView.systemUiVisibility = if (skin.light) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
        setContentView(buildShell())
        val section = savedInstanceState?.getInt("section", -1) ?: -1
        val restored = savedInstanceState?.getString("screen") ?: HOME
        if (restored == SECTION && section in Sections.ALL.indices) openSection(Sections.ALL[section])
        else show(if (restored == SECTION) MORE else restored)
        if (savedInstanceState?.getBoolean("live") == true) openLive()
        if (savedInstanceState == null && CrashReports.takePrompt(this)) {
            showConfirm(
                "Джарвис недавно закрылся с ошибкой. Отправить отчёт разработчику? " +
                    "В нём модель телефона и техническая часть, без ваших команд и переписки.",
                "Отчёт об ошибке",
            ) { startActivity(CrashReports.shareIntent(this)) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheetHost.childCount > 0 -> closeConfirm()
                    live != null -> closeLive()
                    sectionOnly -> finish()
                    current != HOME -> back()
                    else -> moveTaskToBack(true)
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { JarvisEvents.assistantState.collect(::onState) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { JarvisEvents.events.collect(::onEvent) }
        }
        handleIntent(intent)
    }

    /** Тот же запуск (виджет, ассистент, ярлык) для классического экрана: действие, данные и extras — без флагов. */
    private fun classicIntent(source: Intent): Intent = Intent(this, DashboardActivity::class.java).apply {
        source.action?.takeIf { it != Intent.ACTION_MAIN }?.let { action = it }
        source.data?.let { data = it }
        source.extras?.let { putExtras(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", current)
        outState.putBoolean("live", live != null)
        outState.putInt("section", Sections.ALL.indexOf((screens[SECTION] as? SectionScreen)?.section))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_ASSIST) {
            // Виджет и системный вызов равны слову активации. Live запускается только своей кнопкой.
            lifecycleScope.launch {
                if (!VoiceController.isRunning) powerOn()
                repeat(STARTUP_POLLS) {
                    if (VoiceController.isRunning) {
                        delay(WAKE_SETTLE_MS)
                        VoiceController.manualWake()
                        return@launch
                    }
                    delay(STARTUP_POLL_MS)
                }
            }
        } else if (intent.action == ACTION_POWER_ON) {
            powerOn()
        } else if (intent.getBooleanExtra("open_live", false)) {
            openLive()
        }
        intent.getStringExtra(UiMode.EXTRA_OPEN_SECTION)?.let { title ->
            Sections.ALL.firstOrNull { it.title == title }?.let(::openSection)
        }
    }

    override fun onStart() {
        super.onStart()
        // Был включён до закрытия или обновления: поднимаем без лишнего нажатия.
        if (JarvisSettings.voiceWanted(this) && !VoiceController.isRunning &&
            !NativeGuard.isOff(this, NativeGuard.VOICE_AUTOSTART)
        ) {
            // Если запуск голоса уронит приложение, в следующий раз автозапуска не будет.
            NativeGuard.begin(this, NativeGuard.VOICE_AUTOSTART)
            powerOn()
            window.decorView.postDelayed({ NativeGuard.end(this, NativeGuard.VOICE_AUTOSTART) }, AUTOSTART_SAFE_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        VoiceController.hideOverlay()
        OverlayOrbService.hide(this)
        screens[current]?.onShow()
        // Вернулись из орба или разговор завершён в нём: экран Live следует за настоящим состоянием.
        if (VoiceController.liveTalk) openLive()
        live?.sync()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && VoiceController.liveTalk && Settings.canDrawOverlays(this)) VoiceController.showOverlay()
    }

    private fun buildShell(): View {
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            background = if (skin.premium) GradientDrawable().apply { setColor(skin.bg) } else GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(skin.bg, alpha(skin.accent, 1f).let { mix(skin.bg, it, .1f) }, skin.bg),
            )
        }
        // Премиум: живой дым под всеми экранами, оттенок следует за состоянием.
        if (skin.premium) SmokeView(this, skin).also {
            smoke = it
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        val col = column()
        content = FrameLayout(this)
        col.addView(content, lp(h = 0, weight = 1f))
        navHost = buildNav()
        col.addView(navHost, lp(h = dp(92)).apply { topMargin = -dp(24) })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        liveHost = FrameLayout(this)
        root.addView(liveHost, FrameLayout.LayoutParams(-1, -1))
        sheetHost = FrameLayout(this)
        root.addView(sheetHost, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.BOTTOM })
        return root
    }

    private fun buildNav(): View {
        if (skin.premium) return buildPremiumNav()
        val host = FrameLayout(this).apply { clipChildren = false }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(mix(skin.bg, skin.accent, .1f))
                cornerRadii = floatArrayOf(dp(26f).toFloat(), dp(26f).toFloat(), dp(26f).toFloat(), dp(26f).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(1, skin.line)
            }
        }
        fun tab(id: String, icon: String, title: String) {
            val ic = IconView(this, icon, skin.muted, 20)
            val t = label(title, 10.5f, skin.muted, gravity = Gravity.CENTER, lines = 1)
            val box = column().apply {
                gravity = Gravity.CENTER
                addView(ic)
                addView(t, lp(h = -2).apply { topMargin = dp(3) })
                tap { if (id == LIVE) openLive() else show(id) }
            }
            box.contentDescription = title
            tabs[id] = ic to t
            bar.addView(box, lp(0, dp(60), 1f))
        }
        tab(HOME, Ic.HOME, "Главная")
        tab(CHAT, Ic.CHAT, "Диалог")
        tab(MEMORY, Ic.BRAIN, "Память")
        bar.addView(View(this), lp(dp(82), 1))
        tab(LIVE, Ic.LIVE, "Live")
        tab(SKILLS, Ic.GRID, "Навыки")
        tab(MORE, Ic.DOTS, "Ещё")
        host.addView(bar, FrameLayout.LayoutParams(-1, dp(68)).apply { gravity = Gravity.BOTTOM })
        mic = FrameLayout(this).apply {
            contentDescription = "Говорить"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(40f).toFloat()
                colors = intArrayOf(alpha(skin.accent, .55f), mix(skin.bg, skin.accent, .08f))
                setStroke(dp(2), skin.accent)
            }
            elevation = dp(8f).toFloat()
            addView(IconView(context, Ic.MIC, skin.text, 30), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            tap { onMic() }
        }
        host.addView(mic, FrameLayout.LayoutParams(dp(74), dp(74)).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
        return host
    }

    /** Премиум: стеклянная панель-капсула с орбом-микрофоном посередине. */
    private fun buildPremiumNav(): View {
        val host = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(skin, 30).apply { cornerRadius = dp(30).toFloat() }
            setPadding(dp(6), 0, dp(6), 0)
        }
        fun tab(id: String, icon: String, title: String) {
            val ic = IconView(this, icon, skin.muted, 20)
            val t = label(title, 9.5f, skin.muted, gravity = Gravity.CENTER, lines = 1)
            val box = column().apply {
                gravity = Gravity.CENTER
                addView(ic)
                addView(t, lp(h = -2).apply { topMargin = dp(4) })
                tap { if (id == LIVE) openLive() else show(id) }
            }
            box.contentDescription = title
            tabs[id] = ic to t
            bar.addView(box, lp(0, dp(62), 1f))
        }
        tab(HOME, Ic.HOME, "Главная")
        tab(CHAT, Ic.CHAT, "Диалог")
        tab(MEMORY, Ic.BRAIN, "Память")
        bar.addView(View(this), lp(dp(70), 1))
        tab(LIVE, Ic.LIVE, "Live")
        tab(SKILLS, Ic.GRID, "Навыки")
        tab(MORE, Ic.DOTS, "Ещё")
        host.addView(bar, FrameLayout.LayoutParams(-1, dp(66)).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(12), 0, dp(12), dp(10))
        })
        mic = FrameLayout(this).apply {
            contentDescription = "Говорить"
            background = orbDrawable(skin.accent, skin.accent2)
            elevation = dp(10f).toFloat()
            outlineAmbientShadowColor = skin.accent
            outlineSpotShadowColor = skin.accent
            addView(IconView(context, Ic.MIC, skin.onAccent, 24), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            tap { onMic() }
        }
        host.addView(mic, FrameLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(6) })
        return host
    }

    private fun orbDrawable(acc: Int, acc2: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(40f).toFloat()
        setGradientCenter(.35f, .3f)
        colors = intArrayOf(android.graphics.Color.WHITE, mix(acc, android.graphics.Color.WHITE, .6f), acc, acc2, mix(skin.bg, acc2, .3f))
        setStroke(dp(5), skin.bg)
    }

    fun show(id: String) {
        if (id == current) return
        if (id != SECTION) previous = if (current == SECTION) MORE else current.ifEmpty { HOME }
        screens[current]?.onHide()
        current = id
        content.removeAllViews()
        try {
            val screen = screens.getOrPut(id) { createScreen(id) }
            content.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
            screen.onShow()
        } catch (e: Exception) {
            // Экран не собрался на этом устройстве: вместо вылета сообщение и отчёт.
            screens.remove(id)
            CrashReports.record(this, "экран «$id»", e)
            content.removeAllViews()
            content.addView(column(12).pad(24, 60).apply {
                addView(label("Этот экран не открылся", 20f, skin.text, Fonts.display(this@JarvisActivity)))
                addView(label("Отчёт сохранён. Отправить его можно в «Ещё → Помощь и диагностика».", 14f, skin.muted))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val active = if (id == SECTION) MORE else id
        tabs.forEach { (key, pair) ->
            val c = if (key == active) (if (skin.premium) skin.text else skin.accent) else skin.muted
            pair.first.tint = c
            pair.first.invalidate()
            pair.second.setTextColor(c)
        }
    }

    fun back() {
        // Раздел, открытый из гида отдельным окном, закрывается и возвращает в гид.
        if (sectionOnly) finish() else show(if (current == SECTION) MORE else previous.takeIf { it != current } ?: HOME)
    }

    fun openSection(section: MoreSection) {
        (screens.getOrPut(SECTION) { SectionScreen(this) } as SectionScreen).bind(section)
        show(SECTION)
    }

    private fun createScreen(id: String): Screen = when (id) {
        CHAT -> if (skin.premium) PremiumChatScreen(this) else ChatScreen(this)
        MEMORY -> if (skin.premium) PremiumMemoryScreen(this) else MemoryScreen(this)
        SKILLS -> if (skin.premium) PremiumSkillsScreen(this) else SkillsScreen(this)
        MORE -> MoreScreen(this)
        ROUTINES -> RoutinesScreen(this)
        STATUS -> StatusScreen(this)
        SCAN -> ScanScreen(this)
        SECTION -> SectionScreen(this)
        else -> if (skin.premium) PremiumHomeScreen(this) else HomeScreen(this)
    }

    fun openLive() {
        if (live != null) return
        val screen = LiveScreen(this)
        live = screen
        liveHost.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        systemBars(screen.stageColor, light = false)
        screen.onShow()
        screen.onState(assistantState)
    }

    /** Live всегда тёмный: в светлой теме системные полосы на время Live тоже тёмные. */
    private fun systemBars(color: Int, light: Boolean) {
        window.statusBarColor = color
        window.navigationBarColor = color
        window.decorView.systemUiVisibility = if (light) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
    }

    fun closeLive() {
        val screen = live ?: return
        screen.onHide()
        liveHost.removeAllViews()
        live = null
        systemBars(skin.bg, skin.light)
    }

    /** Включить ассистента. Разрешения спрашиваем только когда они нужны. */
    fun powerOn() {
        pendingLive = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        askNotificationsThenStart()
    }

    private fun askNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            VoiceController.start(this)
        }
    }

    fun powerOff() {
        VoiceController.liveTalk = false
        VoiceController.stop(this)
    }

    /** Микрофон работает как слово активации. */
    private fun onMic() {
        if (current != HOME) show(HOME)
        when {
            !isOn -> screens[HOME]?.hint("Сначала нажмите на ядро, сэр")
            assistantState == AssistantState.LISTENING ||
                assistantState == AssistantState.THINKING ||
                assistantState == AssistantState.EXECUTING -> VoiceController.cancelTurn(this)
            else -> VoiceController.manualWake()
        }
    }

    /** Текстовый запрос тем же путём, что голос. */
    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!VoiceController.isRunning) powerOn()
        lifecycleScope.launch {
            repeat(STARTUP_POLLS) {
                if (VoiceController.isRunning) {
                    ConversationStore.pushUser(clean)
                    screens[CHAT]?.onData()
                    VoiceController.notifyRequest(UserRequest(clean))
                    return@launch
                }
                delay(STARTUP_POLL_MS)
            }
            ConversationStore.pushAi("Сервис голоса ещё запускается, сэр. Повторите через минуту.")
            screens[CHAT]?.onData()
        }
    }

    private fun onState(state: AssistantState) {
        assistantState = state
        if (state == AssistantState.IDLE || state == AssistantState.READY) answerStep = -1
        if (state == AssistantState.LISTENING) answerStep = 0
        if (state == AssistantState.SPEAKING) answerStep = 4
        screens[HOME]?.onState(state)
        screens[CHAT]?.onState(state)
        live?.onState(state)
        // Выключен: дым остаётся в цвете схемы, только приглушён.
        smoke?.tint = if (state == AssistantState.OFFLINE || state == AssistantState.ERROR) mix(skin.metal, skin.accent, .45f) else stateTone(skin, state)
        if (skin.premium) {
            val tone = stateTone(skin, state)
            mic.background = orbDrawable(tone, if (tone == skin.accent) skin.accent2 else mix(tone, skin.bg, .4f))
        }
    }

    private fun onEvent(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.TranscriptUpdated -> lastPartial = event.text
            is AssistantEvent.RequestReceived -> {
                lastRequest = event.text
                lastPartial = ""
                answerStep = 1
                ConversationStore.pushUser(event.text)
                ActivityFeed.push("Запрос", event.text, ActivityFeed.Item.Kind.VOICE)
                // Ответили голосом: окно подтверждения больше не нужно.
                if (askingYesNo) closeConfirm()
            }
            is AssistantEvent.ResponseGenerated -> {
                lastResponse = event.text
                answerStep = 3
                ConversationStore.pushAi(event.text)
                ActivityFeed.push("Ответ", event.text.take(FEED_TEXT), ActivityFeed.Item.Kind.VOICE)
                // Любой другой ответ значит, что вопроса больше нет: кнопка «Да» не должна оставаться живой.
                if (event.text.contains(YES_OR_NO)) showConfirm(event.text.substringBefore(YES_OR_NO).trim())
                else if (askingYesNo) closeConfirm()
            }
            is AssistantEvent.IntentResolved -> answerStep = 2
            is AssistantEvent.ToolExecuted -> {
                val pending = event.result.status == ToolResultStatus.NEEDS_CONFIRMATION
                ActivityFeed.push(
                    event.result.toolId,
                    event.result.message.orEmpty(),
                    if (pending) ActivityFeed.Item.Kind.CONFIRM else ActivityFeed.Item.Kind.TOOL,
                    pendingConfirm = pending,
                )
                if (pending) showConfirm(event.result.message ?: "Подтвердите действие, сэр.")
            }
            AssistantEvent.WakeDetected -> ActivityFeed.push("Активация", "Услышал обращение", ActivityFeed.Item.Kind.SYSTEM)
            else -> return
        }
        screens[HOME]?.onData()
        screens[CHAT]?.onData()
        live?.onData()
    }

    /** Подтверждение опасного действия: снизу, голосом можно ответить так же. */
    fun showConfirm(message: String, title: String = "Подтвердите, сэр", onYes: (() -> Unit)? = null) {
        sheetHost.removeAllViews()
        confirmJob?.cancel()
        askingYesNo = onYes == null
        val box = column(12).pad(16, 18).apply {
            background = if (skin.premium) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(skin.bg, skin.accent, .1f), skin.bg)).apply {
                cornerRadii = floatArrayOf(dp(28f).toFloat(), dp(28f).toFloat(), dp(28f).toFloat(), dp(28f).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(1, alpha(skin.warn, .6f))
            } else GradientDrawable().apply {
                setColor(mix(skin.bg, skin.accent, .12f))
                cornerRadii = floatArrayOf(dp(20f).toFloat(), dp(20f).toFloat(), dp(20f).toFloat(), dp(20f).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(1, skin.warn)
            }
            isClickable = true
        }
        box.addView(label(title, 20f, skin.text, Fonts.display(this)))
        box.addView(label(message, 14f, skin.muted))
        fun answer(yes: Boolean) {
            closeConfirm()
            if (onYes != null) {
                if (yes) onYes()
            } else {
                ActivityFeed.all().firstOrNull { it.pendingConfirm }?.let { ActivityFeed.markResolved(it.id, yes) }
                if (VoiceController.isRunning) VoiceController.notifyRequest(UserRequest(if (yes) "да" else "нет"))
            }
        }
        val row = rowLayout(10)
        row.addView(button("Нет", false) { answer(false) }, lp(0, dp(48), 1f))
        row.addView(button("Да", true) { answer(true) }, lp(0, dp(48), 1f))
        box.addView(row)
        sheetHost.addView(box, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
        if (onYes == null) confirmJob = lifecycleScope.launch {
            delay(CONFIRM_TIMEOUT_MS)
            closeConfirm()
        }
    }

    private fun closeConfirm() {
        askingYesNo = false
        confirmJob?.cancel()
        sheetHost.removeAllViews()
    }

    fun button(text: String, primary: Boolean, action: () -> Unit): TextView =
        label(text, 15f, if (primary) skin.onAccent else skin.text, Fonts.body(this, 700), Gravity.CENTER).apply {
            background = if (skin.premium && primary) {
                // Премиум: главная кнопка серебряная, акцент только у Джарвиса.
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.parseColor("#D5DBE4"))).apply { cornerRadius = dp(24).toFloat() }
            } else if (skin.premium) glassBg(skin, 24).apply { cornerRadius = dp(24).toFloat() }
            else glassBg(skin, 12, if (primary) skin.accent else skin.glass, if (primary) skin.accent else skin.line)
            this.gravity = Gravity.CENTER
            tap(action)
        }

    companion object {
        const val ACTION_POWER_ON = "com.jarvis.assistant.POWER_ON"
        const val HOME = "home"
        const val CHAT = "chat"
        const val MEMORY = "memory"
        const val LIVE = "live"
        const val SKILLS = "skills"
        const val MORE = "more"
        const val ROUTINES = "routines"
        const val STATUS = "status"
        const val SCAN = "scan"
        const val SECTION = "section"
        private const val AUTOSTART_SAFE_MS = 20_000L
        private const val STARTUP_POLLS = 40
        private const val WAKE_SETTLE_MS = 400L
        private const val STARTUP_POLL_MS = 500L
        private const val FEED_TEXT = 120
        private const val YES_OR_NO = "Да или нет"
        private const val CONFIRM_TIMEOUT_MS = 20_000L
    }
}

fun mix(a: Int, b: Int, k: Float): Int {
    val r = android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * k
    val g = android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * k
    val bl = android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * k
    return android.graphics.Color.rgb(r.toInt(), g.toInt(), bl.toInt())
}

/** Экран внутри окна. */
abstract class Screen(protected val host: JarvisActivity) {
    protected open val skin: Skin get() = host.skin
    abstract val view: View
    open fun onShow() = Unit
    open fun onHide() = Unit
    open fun onState(state: AssistantState) = Unit
    /** Пришли новые реплики или события. */
    open fun onData() = Unit
    /** Короткая подсказка на главной. */
    open fun hint(text: String) = Unit

    /** Шапка: стрелка назад только у вложенных экранов. */
    protected fun header(title: String, withBack: Boolean): View {
        if (skin.premium) return premiumHeader(title, withBack)
        val row = host.rowLayout(8).pad(14, 10)
        if (withBack) row.addView(FrameLayout(host).apply {
            addView(IconView(host, Ic.BACK, skin.accent), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            contentDescription = "Назад"
            tap { host.back() }
        }, lp(host.dp(42), host.dp(42)))
        val t = host.label(title, 22f, skin.text, Fonts.display(host))
        t.setPadding(host.dp(10), 0, 0, 0)
        t.background = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
        }
        row.addView(View(host).apply { setBackgroundColor(skin.accent) }, lp(host.dp(3), host.dp(24)))
        row.addView(t, lp(0, -2, 1f))
        return row
    }

    /** Премиум-шапка: моно-строка над крупным заголовком, назад — стеклянный круг справа. */
    private fun premiumHeader(title: String, withBack: Boolean): View {
        val row = host.rowLayout(10).apply { setPadding(host.dp(20), host.dp(16), host.dp(16), host.dp(12)) }
        val text = host.column(5)
        text.addView(host.monoLabel(meta(), 10f, skin.metal).apply { letterSpacing = .1f })
        text.addView(host.label(title, 27f, skin.text, Fonts.display(host, 600)).apply { letterSpacing = -.015f })
        row.addView(text, lp(0, -2, 1f))
        if (withBack) row.addView(FrameLayout(host).apply {
            background = host.glassBg(skin, 20).apply { cornerRadius = host.dp(20).toFloat() }
            addView(IconView(host, Ic.BACK, skin.text, 20), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            contentDescription = "Назад"
            tap { host.back() }
        }, lp(host.dp(40), host.dp(40)))
        return row
    }

    /** Строка над заголовком премиум-шапки. */
    protected open fun meta(): String = "JARVIS · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    protected fun scroll(body: LinearLayout): View = android.widget.ScrollView(host).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(host.dp(14), 0, host.dp(14), host.dp(40))
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }

    protected fun section(title: String): TextView =
        host.label(title.uppercase(), 13f, skin.muted, Fonts.display(host)).apply {
            letterSpacing = .08f
            setPadding(host.dp(2), host.dp(8), 0, 0)
        }

    /** Строка-панель: значок, заголовок, подпись и что-то справа. */
    protected fun card(
        title: String,
        sub: String? = null,
        icon: String? = null,
        trailing: View? = null,
        tone: Int = skin.accent,
        titleColor: Int = skin.text,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = host.rowLayout(12).pad(12)
        row.background = host.glassBg(skin)
        if (icon != null) row.addView(FrameLayout(host).apply {
            background = host.glassBg(skin, 12, alpha(tone, .13f), alpha(tone, .35f))
            addView(IconView(host, icon, tone), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, lp(host.dp(44), host.dp(44)))
        val text = host.column()
        text.addView(host.label(title, 15f, titleColor, Fonts.body(host, 600)))
        if (!sub.isNullOrBlank()) text.addView(host.label(sub, 12.5f, skin.muted).apply { setPadding(0, host.dp(2), 0, 0) })
        row.addView(text, lp(0, -2, 1f))
        if (trailing != null) row.addView(trailing, lp(-2, -2))
        else if (onClick != null) row.addView(host.label("›", 18f, skin.muted))
        onClick?.let { row.tap(it) }
        return row
    }
}
