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
import com.jarvis.app.UsageGuideActivity
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
    private val tabs = LinkedHashMap<String, Pair<IconView, TextView>>()
    private val screens = HashMap<String, Screen>()
    private var current = ""
    private var previous = HOME
    private var live: LiveScreen? = null
    private var pendingLive = false
    private var confirmJob: Job? = null

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askNotificationsThenStart()
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        VoiceController.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OnboardingActivity.needsOnboarding(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        if (UsageGuideActivity.needsGuide(this)) {
            startActivity(Intent(this, UsageGuideActivity::class.java))
            finish()
            return
        }
        skin = Skins.current(this)
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheetHost.childCount > 0 -> sheetHost.removeAllViews()
                    live != null -> closeLive()
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
        } else if (intent.getBooleanExtra("open_live", false)) {
            openLive()
        }
    }

    override fun onStart() {
        super.onStart()
        // Был включён до закрытия или обновления: поднимаем без лишнего нажатия.
        if (JarvisSettings.voiceWanted(this) && !VoiceController.isRunning) powerOn()
    }

    override fun onResume() {
        super.onResume()
        VoiceController.hideOverlay()
        OverlayOrbService.hide(this)
        screens[current]?.onShow()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && VoiceController.liveTalk && Settings.canDrawOverlays(this)) VoiceController.showOverlay()
    }

    private fun buildShell(): View {
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(skin.bg, alpha(skin.accent, 1f).let { mix(skin.bg, it, .1f) }, skin.bg),
            )
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

    fun show(id: String) {
        if (id == current) return
        if (id != SECTION) previous = if (current == SECTION) MORE else current.ifEmpty { HOME }
        screens[current]?.onHide()
        current = id
        val screen = screens.getOrPut(id) { createScreen(id) }
        content.removeAllViews()
        content.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        screen.onShow()
        val active = if (id == SECTION) MORE else id
        tabs.forEach { (key, pair) ->
            val c = if (key == active) skin.accent else skin.muted
            pair.first.tint = c
            pair.first.invalidate()
            pair.second.setTextColor(c)
        }
    }

    fun back() = show(if (current == SECTION) MORE else previous.takeIf { it != current } ?: HOME)

    fun openSection(section: MoreSection) {
        (screens.getOrPut(SECTION) { SectionScreen(this) } as SectionScreen).bind(section)
        show(SECTION)
    }

    private fun createScreen(id: String): Screen = when (id) {
        CHAT -> ChatScreen(this)
        MEMORY -> MemoryScreen(this)
        SKILLS -> SkillsScreen(this)
        MORE -> MoreScreen(this)
        ROUTINES -> RoutinesScreen(this)
        STATUS -> StatusScreen(this)
        SCAN -> ScanScreen(this)
        SECTION -> SectionScreen(this)
        else -> HomeScreen(this)
    }

    fun openLive() {
        if (live != null) return
        val screen = LiveScreen(this)
        live = screen
        liveHost.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        screen.onShow()
        screen.onState(assistantState)
    }

    fun closeLive() {
        val screen = live ?: return
        screen.onHide()
        liveHost.removeAllViews()
        live = null
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
            !isOn -> (screens[HOME] as? HomeScreen)?.hint("Сначала нажмите на ядро, сэр")
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
        live?.onState(state)
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
            }
            is AssistantEvent.ResponseGenerated -> {
                lastResponse = event.text
                answerStep = 3
                ConversationStore.pushAi(event.text)
                ActivityFeed.push("Ответ", event.text.take(FEED_TEXT), ActivityFeed.Item.Kind.VOICE)
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
        val box = column(12).pad(16, 18).apply {
            background = GradientDrawable().apply {
                setColor(mix(skin.bg, skin.accent, .12f))
                cornerRadii = floatArrayOf(dp(20f).toFloat(), dp(20f).toFloat(), dp(20f).toFloat(), dp(20f).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(1, skin.warn)
            }
            isClickable = true
        }
        box.addView(label(title, 20f, skin.text, Fonts.display(this)))
        box.addView(label(message, 14f, skin.muted))
        fun answer(yes: Boolean) {
            confirmJob?.cancel()
            sheetHost.removeAllViews()
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
            sheetHost.removeAllViews()
        }
    }

    fun button(text: String, primary: Boolean, action: () -> Unit): TextView =
        label(text, 15f, if (primary) skin.onAccent else skin.text, Fonts.body(this, 700), Gravity.CENTER).apply {
            background = glassBg(skin, 12, if (primary) skin.accent else skin.glass, if (primary) skin.accent else skin.line)
            this.gravity = Gravity.CENTER
            tap(action)
        }

    companion object {
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
        private const val STARTUP_POLLS = 40
        private const val WAKE_SETTLE_MS = 400L
        private const val STARTUP_POLL_MS = 500L
        private const val FEED_TEXT = 120
        private const val CONFIRM_TIMEOUT_MS = 10_000L
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
    protected val skin: Skin get() = host.skin
    abstract val view: View
    open fun onShow() = Unit
    open fun onHide() = Unit
    open fun onState(state: AssistantState) = Unit
    /** Пришли новые реплики или события. */
    open fun onData() = Unit

    /** Шапка: стрелка назад только у вложенных экранов. */
    protected fun header(title: String, withBack: Boolean): View {
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
