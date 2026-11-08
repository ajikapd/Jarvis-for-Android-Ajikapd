package com.jarvis.app

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.VISIBLE
import android.view.View.GONE
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ImageView
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.model.AssistantState
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.companion.ObservationDiagnosticsBuffer
import com.jarvis.app.companion.CompanionBehavior
import com.jarvis.app.companion.CompanionMemory
import com.jarvis.app.companion.CompanionProactive
import com.jarvis.app.companion.SystemStateObserver
import com.jarvis.app.companion.UsageStatsObserver
import com.jarvis.core.behavior.BehaviorFeedback
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.memory.MemoryCandidatePipeline
import com.jarvis.core.memory.MemoryDisposition
import com.jarvis.core.memory.MemoryKind
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.onboarding.PermissionsHelper
import com.jarvis.screencontrol.JarvisAccessibilityService
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private val reminderBook by lazy { AndroidReminderBook(this) }

    private lateinit var screenContainer: FrameLayout
    private lateinit var bottomNav: LinearLayout

    private val railTabs = listOf(
        TAB_HOME to R.drawable.ic_fui_core,
        TAB_LIVE to R.drawable.ic_fui_live,
        TAB_ASSISTANT to R.drawable.ic_fui_chat,
        TAB_SCENARIOS to R.drawable.ic_fui_bolt,
        TAB_MEMORY_SCREEN to R.drawable.ic_fui_grid,
        TAB_TOOLS to R.drawable.ic_fui_tools,
        TAB_ACTIVITY to R.drawable.ic_fui_hex,
        TAB_MORE to R.drawable.ic_fui_profile,
    )
    private var assistantState = AssistantState.OFFLINE
    private var currentScreenIndex = 0
    private var commandsFilter = ""
    private var commandsCategory: String? = null
    private var pendingSettingsTab: String? = null
    /** Control Center secondary: memory|commands|automations|companion|models|advanced|null */
    private var controlSecondary: String? = null
    private var activityFilter: ActivityFeed.Item.Kind? = null
    private var assistantThreadFilter: String = "today"
    private var activitySearchQuery: String = ""
    private var memoryCategory: String? = null

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_OPEN_SECONDARY = "open_secondary"
        const val TAB_HOME = 0
        const val TAB_ASSISTANT = 1
        const val TAB_LIVE = 2
        const val TAB_ACTIVITY = 3
        const val TAB_MORE = 4
        const val TAB_SCENARIOS = 5
        const val TAB_MEMORY_SCREEN = 6
        const val TAB_TOOLS = 7
        /** Alias: More / Control Center */
        const val TAB_SETTINGS = 4
        /** Opens More; pair with EXTRA_OPEN_SECONDARY=commands when needed. */
        const val TAB_COMMANDS = 4
        const val TAB_MEMORY = 101
    }
    private var latestTranscript = ""
    private var latestRequest = ""
    private var latestResponse = ""
    private var latestIntent = ""
    private var latestTool = ""

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionOrStart()
    }

    private var pendingTorch: Boolean? = null
    private var pendingViewfinder = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val want = pendingTorch
        pendingTorch = null
        if (granted && want != null) setTorch(want)
        if (pendingViewfinder) {
            pendingViewfinder = false
            if (granted) setLiveViewfinder(true)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        VoiceController.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Разрешения, выбор интерфейса и гид идут раньше самого интерфейса.
        UiMode.stepIntent(this, UiMode.route(this))?.let {
            startActivity(it)
            finish()
            return
        }
        setContentView(R.layout.activity_dashboard_shell)
        ThemeApplier.applyToActivity(this)

        screenContainer = findViewById(R.id.screenContainer)
        bottomNav = findViewById(R.id.bottomNav)
        buildBottomNavigation()
        findViewById<TextView>(R.id.btnAccentCycle)?.setOnClickListener {
            val order = JarvisSettings.THEMES
            val cur = JarvisSettings.theme(this)
            val next = order[((order.indexOf(cur).takeIf { it >= 0 } ?: -1) + 1) % order.size]
            JarvisSettings.setTheme(this, next)
            ThemeApplier.recreateWithTheme(this)
        }
        if (savedInstanceState != null) {
            controlSecondary = savedInstanceState.getString("control_secondary")
            currentScreenIndex = savedInstanceState.getInt("current_tab", TAB_HOME)
        }
        intent.getStringExtra(EXTRA_OPEN_SECONDARY)?.let { controlSecondary = it }
        val rawTab = intent.getIntExtra(EXTRA_OPEN_TAB, -1)
        val openTab = when {
            savedInstanceState != null -> currentScreenIndex
            intent.getBooleanExtra("open_live", false) -> TAB_LIVE
            rawTab == TAB_MEMORY -> TAB_MEMORY_SCREEN
            controlSecondary == "commands" -> TAB_TOOLS
            controlSecondary == "memory" -> TAB_MEMORY_SCREEN
            controlSecondary == "automations" -> TAB_SCENARIOS
            rawTab in 0..7 -> rawTab
            else -> TAB_HOME
        }
        showScreen(openTab)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JarvisEvents.assistantState.collect(::renderAssistantState)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JarvisEvents.events.collect(::handleAssistantEvent)
            }
        }
        maybeHandleSystemAssist(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_tab", currentScreenIndex)
        outState.putString("control_secondary", controlSecondary)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleSystemAssist(intent)
        intent.getStringExtra(EXTRA_OPEN_SECONDARY)?.let { controlSecondary = it }
        val tab = intent.getIntExtra(EXTRA_OPEN_TAB, -1)
        if (tab == TAB_MEMORY) {
            if (::screenContainer.isInitialized) showScreen(TAB_MEMORY_SCREEN)
        } else if (tab in 0..7 && ::screenContainer.isInitialized) {
            showScreen(tab)
        }
    }

    /**
     * Stage 4.5: ACTION_ASSIST / system assist → единый VoicePipeline
     * (тот же путь, что VIS Session handoff).
     */
    private fun maybeHandleSystemAssist(intent: Intent?) {
        if (intent?.action != Intent.ACTION_ASSIST) return
        Log.i("Jarvis.Assist", "ACTION_ASSIST → VoicePipeline")
        VoiceController.startFromSystemAssist(applicationContext)
        // Live tab — пользователь видит, что помощник слушает
        if (::screenContainer.isInitialized) {
            showScreen(TAB_LIVE)
        }
    }

    override fun onStart() {
        super.onStart()
        VoiceController.hideOverlay()
        OverlayOrbService.hide(this)
    }

    override fun onResume() {
        super.onResume()
        VoiceController.hideOverlay()
        OverlayOrbService.hide(this)
        if (::screenContainer.isInitialized) tickShellClock()
    }

    override fun onPause() {
        super.onPause()
        // Оверлей ТОЛЬКО при сворачивании активной live-сессии
        if (!isFinishing && VoiceController.isLiveSession && Settings.canDrawOverlays(this)) {
            VoiceController.showOverlay()
        }
    }

    override fun onStop() {
        super.onStop()
    }

    private fun screenTitle(index: Int): String = when (index) {
        TAB_HOME -> "Ядро"
        TAB_LIVE -> "Live"
        TAB_ASSISTANT -> "Диалог"
        TAB_SCENARIOS -> "Сценарии"
        TAB_MEMORY_SCREEN -> "Память"
        TAB_TOOLS -> "Инструменты"
        TAB_ACTIVITY -> "Система"
        TAB_MORE -> "Профиль"
        else -> "JARVIS"
    }

    private fun buildBottomNavigation() {
        bottomNav.removeAllViews()
        railTabs.forEach { (tab, icon) ->
            val item = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp2px(44),
                )
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(icon)
                tag = tab
                contentDescription = screenTitle(tab)
                setOnClickListener {
                    HudFeedback.tick(this)
                    controlSecondary = null
                    showScreen(tab)
                }
            }
            bottomNav.addView(item)
        }
        tintBottomNav()
    }

    private fun tintBottomNav() {
        if (!::bottomNav.isInitialized) return
        val theme = JarvisSettings.theme(this)
        val active = ThemeApplier.primaryColor(theme)
        val muted = ThemeApplier.mutedColor(theme)
        for (position in 0 until bottomNav.childCount) {
            val item = bottomNav.getChildAt(position) as? ImageView ?: continue
            val on = item.tag == currentScreenIndex
            item.setColorFilter(if (on) active else muted)
            item.background = if (on) ContextCompat.getDrawable(this, R.drawable.bg_nav_mark) else null
        }
    }

    private fun openControlSecondary(key: String) {
        controlSecondary = key
        showScreen(TAB_MORE)
    }

    private fun showScreen(index: Int) {
        currentScreenIndex = index
        screenContainer.removeAllViews()
        val screenLayout = when (index) {
            TAB_HOME -> R.layout.screen_home
            TAB_ASSISTANT -> R.layout.screen_assistant
            TAB_LIVE -> R.layout.screen_live
            TAB_ACTIVITY -> R.layout.screen_activity
            TAB_SCENARIOS -> R.layout.screen_automations
            TAB_MEMORY_SCREEN -> R.layout.screen_memory
            TAB_TOOLS -> R.layout.screen_skills
            TAB_MORE -> when (controlSecondary) {
                "commands" -> R.layout.screen_skills
                "memory" -> R.layout.screen_memory
                "automations" -> R.layout.screen_automations
                else -> R.layout.screen_settings
            }
            else -> R.layout.screen_not_implemented
        }
        val screen = LayoutInflater.from(this).inflate(screenLayout, screenContainer, false)
        if (index == TAB_MORE && controlSecondary != null && controlSecondary !in
            setOf("automations", "companion", "models", "advanced")
        ) {
            // memory / commands: prepend back affordance
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_bg))
            }
            val back = TextView(this).apply {
                text = "← Кабинет"
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                textSize = 12f
                setPadding(dp2px(16), dp2px(10), dp2px(16), dp2px(6))
                setOnClickListener {
                    controlSecondary = null
                    showScreen(TAB_MORE)
                }
            }
            wrap.addView(back)
            wrap.addView(
                screen,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            screenContainer.addView(wrap)
        } else {
            screenContainer.addView(screen)
        }

        val pageName = when {
            index == TAB_MORE && controlSecondary == "companion" -> "Присутствие"
            index == TAB_MORE && controlSecondary == "models" -> "Модели"
            index == TAB_MORE && controlSecondary == "advanced" -> "Профиль"
            else -> screenTitle(index)
        }
        findViewById<TextView>(R.id.statusTitle)?.text = pageName
        tickShellClock()
        screen.findViewById<TextView>(R.id.placeholderTitle)?.text = "${screenTitle(index)} · скоро"

        when (index) {
            TAB_HOME -> setupHomeScreen(screen)
            TAB_ASSISTANT -> setupAssistantScreen(screen)
            TAB_LIVE -> {
                screen.findViewById<ConsciousnessView>(R.id.liveOrb)?.onTap = {
                    if (assistantState == AssistantState.OFFLINE || assistantState == AssistantState.ERROR) {
                        activateAssistant(liveMode = true)
                    }
                }
                screen.findViewById<View>(R.id.liveActivate)?.setOnClickListener {
                    if (assistantState == AssistantState.OFFLINE || assistantState == AssistantState.ERROR) {
                        activateAssistant(liveMode = true)
                    } else if (assistantState != AssistantState.STARTING) {
                        VoiceController.stop(this)
                    }
                }
                screen.findViewById<View>(R.id.liveStop)?.setOnClickListener {
                    HudFeedback.key(it)
                    if (assistantState != AssistantState.OFFLINE) VoiceController.stop(this)
                }
                screen.findViewById<View>(R.id.liveMinimize)?.setOnClickListener {
                    HudFeedback.key(it)
                    if (Settings.canDrawOverlays(this)) {
                        OverlayOrbService.show(this)
                        moveTaskToBack(true)
                    } else {
                        android.widget.Toast.makeText(
                            this,
                            "Нужно разрешение «поверх других окон» для свернуть в оверлей.",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                screen.findViewById<View>(R.id.liveInterrupt)?.setOnClickListener {
                    HudFeedback.key(it)
                    VoiceController.stopSpeaking()
                    android.widget.Toast.makeText(this, "Ответ прерван", android.widget.Toast.LENGTH_SHORT).show()
                }
                screen.findViewById<View>(R.id.liveOpenAssistant)?.setOnClickListener {
                    showScreen(TAB_ASSISTANT)
                }
                val persona = JarvisSettings.persona(this).lowercase(Locale.getDefault())
                screen.findViewById<TextView>(R.id.livePersonaChip)?.text = "персона · $persona"
                screen.findViewById<TextView>(R.id.liveModelChip)?.text =
                    if (JarvisSettings.preferCloudLlm(this)) "модель · hybrid" else "модель · local"
                screen.findViewById<TextView>(R.id.liveNetChip)?.text =
                    "asr · ${JarvisSettings.asrMode(this).lowercase(Locale.getDefault())}"
                wireLiveControls(screen)
                startLiveTimer(screen)
            }
            TAB_ACTIVITY -> setupActivityScreen(screen)
            TAB_SCENARIOS -> setupAutomationsScreen(screen)
            TAB_MEMORY_SCREEN -> setupMemoryScreen(screen)
            TAB_TOOLS -> setupCommandsScreen(screen)
            TAB_MORE -> when (controlSecondary) {
                "commands" -> setupCommandsScreen(screen)
                "memory" -> setupMemoryScreen(screen)
                "automations" -> setupAutomationsScreen(screen)
                "companion" -> {
                    pendingSettingsTab = "companion"
                    setupSettingsToggles(screen)
                }
                "models" -> {
                    pendingSettingsTab = "models"
                    setupSettingsToggles(screen)
                }
                else -> {
                    pendingSettingsTab = if (controlSecondary == "advanced") "main" else pendingSettingsTab
                    setupSettingsToggles(screen)
                }
            }
        }
        renderAssistantState(assistantState)
        renderVoiceContent()
        updateTopRuntimeState()
        ThemeApplier.applyToActivity(this)
        ThemeApplier.applyToScreen(screenContainer)
        // Re-tint nav after theme walk
        tintBottomNav()
    }

    private fun wireSecondaryBack(screen: View) {
        // settings layout has no back — inject via toast strip at top if missing
        val existing = screen.findViewById<TextView>(R.id.btnBackToControl)
        if (existing != null) {
            existing.setOnClickListener {
                controlSecondary = null
                showScreen(TAB_MORE)
            }
            return
        }
        val root = screen as? LinearLayout ?: (screen as? android.widget.ScrollView)?.getChildAt(0) as? LinearLayout
        root?.let {
            val back = TextView(this).apply {
                id = R.id.btnBackToControl
                text = "← Кабинет"
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                textSize = 12f
                setPadding(0, dp2px(4), 0, dp2px(8))
                setOnClickListener {
                    controlSecondary = null
                    pendingSettingsTab = null
                    showScreen(TAB_MORE)
                }
            }
            it.addView(back, 0)
        }
    }

    private fun updateTopRuntimeState() {
        findViewById<TextView>(R.id.topRuntimeState)?.apply {
            val cloud = JarvisSettings.preferCloudLlm(this@DashboardActivity) &&
                JarvisSettings.cloudConfigured(this@DashboardActivity)
            text = if (cloud) "гибрид" else "локально"
            setTextColor(
                ContextCompat.getColor(
                    this@DashboardActivity,
                    if (cloud) R.color.jarvis_cyan else R.color.jarvis_green,
                ),
            )
        }
    }

    private fun setupHomeScreen(screen: View) {
        screen.findViewById<View>(R.id.activateAssistant)?.setOnClickListener {
            Log.d("Jarvis.Debug", "activate clicked state=$assistantState")
            if (assistantState == AssistantState.OFFLINE || assistantState == AssistantState.ERROR) {
                activateAssistant()
            } else if (assistantState != AssistantState.STARTING) {
                VoiceController.stop(this)
            }
        }
        screen.findViewById<View>(R.id.homeToChat)?.setOnClickListener {
            showScreen(TAB_ASSISTANT)
        }
        fun paintHome() {
            val wake = JarvisSettings.wakeEnabled(this)
            val router = JarvisSettings.routerMode(this)
            val model = if (router == "LOCAL") "LOCAL LLM" else JarvisSettings.cloudModelForProfile(this)
            val live = VoiceController.isRunning
            screen.findViewById<TextView>(R.id.homeRouterMode)?.text = "● ROUTER  $router"
            screen.findViewById<TextView>(R.id.homeRouterCycle)?.text = "AI ROUTER\n● $router"
            screen.findViewById<TextView>(R.id.homeWakeToggle)?.text =
                if (wake) "WAKE ENGINE\n● ARMED" else "WAKE ENGINE\n● PAUSED"
            screen.findViewById<TextView>(R.id.homeOverlayToggle)?.text = "OVERLAY\n● LAUNCH ORB"
            screen.findViewById<TextView>(R.id.homeModelChip)?.text = "MODEL  $model"
            screen.findViewById<TextView>(R.id.homeLatencyChip)?.text = "LAT  —"
            screen.findViewById<TextView>(R.id.homeCoreChip)?.text =
                if (live) "CORE  ACTIVE" else "CORE  STANDBY"
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val ramPct = if (mem.totalMem == 0L) 0 else ((mem.totalMem - mem.availMem) * 100 / mem.totalMem).toInt()
            val ramTag = if (ramPct < 85) "OK" else "HIGH"
            screen.findViewById<TextView>(R.id.homeClock)?.text = "RAM $ramTag · $ramPct%"
        }
        paintHome()
        screen.findViewById<TextView>(R.id.homeRouterCycle)?.setOnClickListener {
            JarvisSettings.cycleRouterMode(this)
            paintHome()
        }
        screen.findViewById<TextView>(R.id.homeWakeToggle)?.setOnClickListener {
            JarvisSettings.setWakeEnabled(this, !JarvisSettings.wakeEnabled(this))
            paintHome()
        }
        screen.findViewById<TextView>(R.id.homeOverlayToggle)?.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                OverlayOrbService.show(this)
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    private fun tickShellClock() {
        findViewById<TextView>(R.id.shellClock)?.text =
            java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
    }

    private fun setupAssistantScreen(screen: View) {
        val thread = screen.findViewById<LinearLayout>(R.id.assistantThread) ?: return
        val composer = screen.findViewById<android.widget.EditText>(R.id.assistantComposer)
        fun styleChip(id: Int, active: Boolean) {
            screen.findViewById<TextView>(id)?.apply {
                background = ContextCompat.getDrawable(
                    this@DashboardActivity,
                    if (active) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@DashboardActivity,
                        if (active) R.color.jarvis_text else R.color.jarvis_muted,
                    ),
                )
            }
        }
        fun applyFilter(filter: String) {
            assistantThreadFilter = filter
            styleChip(R.id.chipThreadToday, filter == "today")
            styleChip(R.id.chipThreadWeek, filter == "week")
            styleChip(R.id.chipThreadFav, filter == "fav")
            renderAssistantThread(thread)
        }
        screen.findViewById<TextView>(R.id.chipThreadToday)?.setOnClickListener { applyFilter("today") }
        screen.findViewById<TextView>(R.id.chipThreadWeek)?.setOnClickListener { applyFilter("week") }
        screen.findViewById<TextView>(R.id.chipThreadFav)?.setOnClickListener { applyFilter("fav") }
        screen.findViewById<TextView>(R.id.btnClearThread)?.setOnClickListener {
            ConversationStore.clear()
            renderAssistantThread(thread)
        }
        screen.findViewById<View>(R.id.assistantMic)?.setOnClickListener {
            if (assistantState == AssistantState.OFFLINE || assistantState == AssistantState.ERROR) {
                activateAssistant()
            } else {
                android.widget.Toast.makeText(this, "Слушаю… скажи команду", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        fun sendComposer() {
            dispatchAssistantPrompt(composer?.text?.toString().orEmpty())
        }
        screen.findViewById<TextView>(R.id.assistantSend)?.setOnClickListener { sendComposer() }
        composer?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendComposer()
                true
            } else false
        }
        fun queuePrompt(text: String) {
            composer?.setText(text)
            sendComposer()
        }
        screen.findViewById<TextView>(R.id.suggestPlan)?.setOnClickListener {
            queuePrompt("Помоги расставить приоритеты на сегодня")
        }
        screen.findViewById<TextView>(R.id.suggestIdea)?.setOnClickListener {
            queuePrompt("Помоги разобраться с новой идеей и разложить её на шаги")
        }
        applyFilter(assistantThreadFilter)
    }

    private fun dispatchAssistantPrompt(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (VoiceController.isRunning) HudFeedback.beep(this, android.media.ToneGenerator.TONE_PROP_ACK)
        screenContainer.findViewById<android.widget.EditText>(R.id.assistantComposer)?.setText("")
        ConversationStore.pushUser(clean)
        ActivityFeed.push("Текстовый запрос", clean, ActivityFeed.Item.Kind.VOICE)
        screenContainer.findViewById<LinearLayout>(R.id.assistantThread)?.let { renderAssistantThread(it) }
        if (!VoiceController.isRunning) activateAssistant()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(350)
            if (VoiceController.isRunning) {
                VoiceController.notifyRequest(com.jarvis.core.common.model.UserRequest(clean))
            } else {
                ConversationStore.pushAi(
                    "Сервис голоса ещё не запущен. Нажми активацию на Главной и повтори.",
                    badge = "RETRY",
                )
                screenContainer.findViewById<LinearLayout>(R.id.assistantThread)?.let { renderAssistantThread(it) }
            }
        }
    }

    private fun renderAssistantThread(container: LinearLayout) {
        container.removeAllViews()
        val bubbles = when (assistantThreadFilter) {
            "week" -> ConversationStore.week()
            "fav" -> ConversationStore.favorites()
            else -> ConversationStore.today()
        }
        if (bubbles.isEmpty()) {
            val prompts = listOf(
                "Запусти диагностику",
                "Включи фонарик",
                "Что в моей памяти?",
                "Статус батареи и сети",
            )
            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = dp2px(8)
                layoutParams = lp
            }
            prompts.chunked(2).forEach { rowLabels ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.bottomMargin = dp2px(8)
                    layoutParams = lp
                }
                rowLabels.forEachIndexed { index, label ->
                    row.addView(
                        TextView(this).apply {
                            text = label
                            gravity = Gravity.CENTER
                            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                            textSize = 11f
                            typeface = android.graphics.Typeface.MONOSPACE
                            background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                            val lp = LinearLayout.LayoutParams(0, dp2px(64), 1f)
                            if (index == 0) lp.marginEnd = dp2px(8)
                            layoutParams = lp
                            setOnClickListener { dispatchAssistantPrompt(label) }
                        },
                    )
                }
                grid.addView(row)
            }
            container.addView(grid)
            val facts = runCatching { BrainGraph.memory(this).count() }.getOrDefault(0)
            val router = JarvisSettings.routerMode(this)
            val model = if (router == "LOCAL") "LOCAL LLM" else JarvisSettings.cloudModelForProfile(this)
            val online = runCatching {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.activeNetwork != null
            }.getOrDefault(false)
            val brief = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = dp2px(8)
                layoutParams = lp
            }
            brief.addView(TextView(this).apply {
                text = "СТАТУС СЕССИИ"
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            brief.addView(TextView(this).apply {
                text = "MODEL  $model\nMEMORY  $facts фактов\nNET  ${if (online) "ONLINE" else "OFFLINE"} · ROUTER  $router"
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_text))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp2px(8), 0, dp2px(8))
            })
            brief.addView(TextView(this).apply {
                text = "ЗАПУСТИТЬ ГОЛОСОВОЙ БРИФИНГ"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_fui_active)
                setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10))
                setOnClickListener {
                    dispatchAssistantPrompt("Кратко: статус батареи, сети и что есть в памяти")
                }
            })
            container.addView(brief)
            return
        }
        bubbles.forEach { b ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(
                    this@DashboardActivity,
                    if (b.role == ConversationStore.Bubble.Role.USER) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
                setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = dp2px(8)
                if (b.role == ConversationStore.Bubble.Role.USER) {
                    lp.marginStart = dp2px(28)
                } else {
                    lp.marginEnd = dp2px(12)
                }
                layoutParams = lp
                setOnLongClickListener {
                    ConversationStore.toggleFavorite(b.id)
                    renderAssistantThread(container)
                    true
                }
            }
            if (b.badge != null) {
                card.addView(
                    TextView(this).apply {
                        text = b.badge
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_green))
                        textSize = 9f
                        typeface = android.graphics.Typeface.MONOSPACE
                    },
                )
            }
            card.addView(
                TextView(this).apply {
                    text = b.text
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_text))
                    textSize = 13f
                    setPadding(0, if (b.badge != null) dp2px(6) else 0, 0, 0)
                },
            )
            if (!b.plan.isNullOrBlank()) {
                card.addView(
                    TextView(this).apply {
                        text = "PLAN · ${b.plan}"
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                        textSize = 10f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setPadding(0, dp2px(6), 0, 0)
                    },
                )
            }
            card.addView(
                TextView(this).apply {
                    text = buildString {
                        append(ConversationStore.formatTime(b.atMs))
                        append(if (b.role == ConversationStore.Bubble.Role.USER) " · you" else " · JARVIS")
                        if (b.favorite) append(" · ★")
                    }
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_faint))
                    textSize = 9f
                    setPadding(0, dp2px(6), 0, 0)
                },
            )
            container.addView(card)
        }
        screenContainer.findViewById<android.widget.ScrollView>(R.id.assistantThreadScroll)?.post {
            screenContainer.findViewById<android.widget.ScrollView>(R.id.assistantThreadScroll)
                ?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private var cpuPrevMs = 0L
    private var cpuPrevWall = 0L
    private var cpuEma = -1f
    private val cpuTimePrev = HashMap<Int, Map<Long, Long>>()

    private fun sysKhz(path: String): Long =
        runCatching { java.io.File(path).readText().trim().toLong() }.getOrDefault(0L)

    private fun readCpuLoad(): Float {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        var weighted = 0.0
        var totalDt = 0.0
        for (i in 0 until cores) {
            val lines = runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/stats/time_in_state").readLines()
            }.getOrNull() ?: continue
            val now = HashMap<Long, Long>()
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val freq = parts[0].toLongOrNull() ?: continue
                val ticks = parts[1].toLongOrNull() ?: continue
                now[freq] = ticks
            }
            if (now.isEmpty()) continue
            val prev = cpuTimePrev[i]
            cpuTimePrev[i] = now
            if (prev == null) continue
            val minF = now.keys.minOrNull() ?: continue
            val maxF = now.keys.maxOrNull() ?: continue
            val span = (maxF - minF).coerceAtLeast(1L)
            for ((freq, ticks) in now) {
                val dt = ticks - (prev[freq] ?: ticks)
                if (dt <= 0L) continue
                weighted += (freq - minF).toDouble() / span.toDouble() * dt
                totalDt += dt
            }
        }
        val instant = if (totalDt > 0.0) {
            (weighted / totalDt * 100.0).toFloat()
        } else {
            var acc = 0f
            var counted = 0
            for (i in 0 until cores) {
                val cur = sysKhz("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                val min = sysKhz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
                    .takeIf { it > 0L }
                    ?: sysKhz("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_min_freq")
                val max = sysKhz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .takeIf { it > 0L }
                    ?: sysKhz("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                if (cur > 0L && max > min && min > 0L) {
                    acc += ((cur - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
                    counted++
                }
            }
            if (counted > 0) acc / counted * 100f else {
                val nowCpu = android.os.Process.getElapsedCpuTime()
                val nowWall = SystemClock.elapsedRealtime()
                if (cpuPrevWall == 0L) {
                    cpuPrevMs = nowCpu
                    cpuPrevWall = nowWall
                    12f
                } else {
                    val dw = (nowWall - cpuPrevWall).coerceAtLeast(1L)
                    val proc = ((nowCpu - cpuPrevMs).coerceAtLeast(0L) * 100f / dw / cores)
                    cpuPrevMs = nowCpu
                    cpuPrevWall = nowWall
                    proc
                }
            }
        }
        cpuEma = if (cpuEma < 0f) instant else cpuEma * 0.72f + instant * 0.28f
        return cpuEma.coerceIn(0f, 100f)
    }

    private fun setupActivityScreen(screen: View) {
        val container = screen.findViewById<LinearLayout>(R.id.activityFeedContainer) ?: return
        fun style(id: Int, on: Boolean) {
            screen.findViewById<TextView>(id)?.background = ContextCompat.getDrawable(
                this,
                if (on) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
            )
        }
        fun apply(kind: ActivityFeed.Item.Kind?) {
            activityFilter = kind
            style(R.id.actChipAll, kind == null)
            style(R.id.actChipVoice, kind == ActivityFeed.Item.Kind.VOICE)
            style(R.id.actChipTools, kind == ActivityFeed.Item.Kind.TOOL)
            style(R.id.actChipConfirm, kind == ActivityFeed.Item.Kind.CONFIRM)
            renderActivityFeed(container)
        }
        screen.findViewById<TextView>(R.id.actChipAll)?.setOnClickListener { apply(null) }
        screen.findViewById<TextView>(R.id.actChipVoice)?.setOnClickListener { apply(ActivityFeed.Item.Kind.VOICE) }
        screen.findViewById<TextView>(R.id.actChipTools)?.setOnClickListener { apply(ActivityFeed.Item.Kind.TOOL) }
        screen.findViewById<TextView>(R.id.actChipConfirm)?.setOnClickListener { apply(ActivityFeed.Item.Kind.CONFIRM) }
        screen.findViewById<TextView>(R.id.activitySearch)?.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                hint = "Поиск…"
                setText(activitySearchQuery)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Найти в активности")
                .setView(input)
                .setNegativeButton("Сброс") { _, _ ->
                    activitySearchQuery = ""
                    renderActivityFeed(container)
                }
                .setPositiveButton("Найти") { _, _ ->
                    activitySearchQuery = input.text.toString().trim()
                    renderActivityFeed(container)
                }
                .show()
        }
        screen.findViewById<TextView>(R.id.btnClearActivity)?.setOnClickListener {
            ActivityFeed.clear()
            renderActivityFeed(container)
            updateJournalStats()
        }
        apply(activityFilter)
        updateJournalStats()
        screen.findViewById<TextView>(R.id.btnOpenTestConsole)?.setOnClickListener {
            startActivity(Intent(this, TestConsoleActivity::class.java))
        }
        val cpuGauge = screen.findViewById<GaugeView>(R.id.gaugeCpu)
        val ramGauge = screen.findViewById<GaugeView>(R.id.gaugeRam)
        val batGauge = screen.findViewById<GaugeView>(R.id.gaugeBattery)
        val chart = screen.findViewById<TelemetryChartView>(R.id.systemChart)
        cpuGauge?.label = "CPU"
        ramGauge?.label = "RAM"
        batGauge?.label = "BAT"
        val sample = object : Runnable {
            override fun run() {
                if (!screen.isAttachedToWindow) return
                val cpu = readCpuLoad()
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val info = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                val ramPct = if (info.totalMem == 0L) 0f else (info.totalMem - info.availMem) * 100f / info.totalMem
                val usedMb = (info.totalMem - info.availMem) / (1024 * 1024)
                val totalMb = info.totalMem / (1024 * 1024)
                val bat = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = bat?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0
                val scale = bat?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                val batPct = if (scale == 0) 0f else level * 100f / scale
                val temp = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                val volt = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
                cpuGauge?.value = cpu
                cpuGauge?.caption = "${cpu.toInt()}%"
                ramGauge?.value = ramPct
                ramGauge?.caption = "$usedMb/$totalMb"
                batGauge?.value = batPct
                batGauge?.caption = "${temp.toInt()}° ${volt}V"
                chart?.push(cpu, ramPct)
                fun plaque(id: Int, title: String, value: String, hot: Boolean) {
                    screen.findViewById<TextView>(id)?.apply {
                        text = "● $title\n$value"
                        setTextColor(
                            ContextCompat.getColor(
                                this@DashboardActivity,
                                if (hot) R.color.jarvis_green else R.color.jarvis_muted,
                            ),
                        )
                    }
                }
                plaque(R.id.svcVoice, "VOICE", if (VoiceController.isRunning) "RUN" else "IDLE", VoiceController.isRunning)
                plaque(R.id.svcWake, "WAKE", if (JarvisSettings.wakeEnabled(this@DashboardActivity)) "ARMED" else "PAUSED", JarvisSettings.wakeEnabled(this@DashboardActivity))
                val router = JarvisSettings.routerMode(this@DashboardActivity)
                plaque(R.id.svcRouter, "ROUTER", router, router != "LOCAL")
                val next = com.jarvis.core.reminder.HudText.next(reminderBook.all(), java.time.LocalDateTime.now())
                plaque(R.id.svcNext, "NEXT", next, !next.startsWith("Напоминаний нет"))
                plaque(
                    R.id.svcPipeline,
                    "ASR / TTS",
                    "${JarvisSettings.asrMode(this@DashboardActivity)} · ${JarvisSettings.ttsMode(this@DashboardActivity)}",
                    true,
                )
                screen.findViewById<TextView>(R.id.latLlm)?.text = if (latestTool.isBlank()) "LLM\n—" else "LLM\n${latestTool.substringAfterLast("·").trim()}"
                chart?.postDelayed(this, 1000)
            }
        }
        val seedRam = runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.totalMem == 0L) 40f else (info.totalMem - info.availMem) * 100f / info.totalMem
        }.getOrDefault(40f)
        chart?.seed(14f, seedRam)
        chart?.post(sample)
    }

    private fun updateJournalStats() {
        val all = ActivityFeed.all()
        screenContainer.findViewById<TextView>(R.id.journalRequests)?.text = all.size.toString()
        screenContainer.findViewById<TextView>(R.id.journalFailed)?.text =
            all.count { it.failed }.toString()
    }

    private fun renderActivityFeed(container: LinearLayout) {
        container.removeAllViews()
        var items = ActivityFeed.filter(activityFilter)
        if (activitySearchQuery.isNotBlank()) {
            items = items.filter {
                it.title.contains(activitySearchQuery, true) ||
                    it.detail.contains(activitySearchQuery, true)
            }
        }
        if (items.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "Событий пока нет. Активируй JARVIS и выполни команду — лента заполнится сама."
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_muted))
                    textSize = 12f
                },
            )
            return
        }
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = dp2px(8)
                layoutParams = lp
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "${ActivityFeed.formatTime(item.atMs)} · ${item.kind.name}"
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_faint))
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                },
            )
            val badge = when {
                item.pendingConfirm -> "WAITING"
                item.failed -> "FAILED"
                else -> "OK"
            }
            head.addView(
                TextView(this).apply {
                    text = badge
                    setTextColor(
                        ContextCompat.getColor(
                            this@DashboardActivity,
                            when (badge) {
                                "WAITING" -> R.color.jarvis_primary
                                "FAILED" -> R.color.jarvis_danger
                                else -> R.color.jarvis_green
                            },
                        ),
                    )
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                },
            )
            card.addView(head)
            card.addView(
                TextView(this).apply {
                    text = item.title
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_text))
                    textSize = 13f
                    setPadding(0, dp2px(6), 0, 0)
                },
            )
            card.addView(
                TextView(this).apply {
                    text = item.detail
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_muted))
                    textSize = 10f
                    setPadding(0, dp2px(4), 0, 0)
                },
            )
            if (item.pendingConfirm) {
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp2px(8), 0, 0)
                }
                actions.addView(
                    TextView(this).apply {
                        text = "ПОДТВЕРДИТЬ"
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                        textSize = 12f
                        setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8))
                        background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_fui_active)
                        setOnClickListener {
                            ActivityFeed.markResolved(item.id, approved = true)
                            renderActivityFeed(container)
                            android.widget.Toast.makeText(
                                this@DashboardActivity,
                                "Подтверждено в UI. Исполнение идёт через голосовой pipeline.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                actions.addView(
                    TextView(this).apply {
                        text = "ОТМЕНА"
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_danger))
                        textSize = 12f
                        setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8))
                        background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        lp.marginStart = dp2px(8)
                        layoutParams = lp
                        setOnClickListener {
                            ActivityFeed.markResolved(item.id, approved = false)
                            renderActivityFeed(container)
                        }
                    },
                )
                card.addView(actions)
            } else if (item.failed) {
                card.addView(
                    TextView(this).apply {
                        text = "Повторить запрос"
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_text2))
                        textSize = 11f
                        setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8))
                        background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_btn_ghost_3)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        lp.topMargin = dp2px(8)
                        layoutParams = lp
                        setOnClickListener {
                            if (latestRequest.isNotBlank()) {
                                if (!VoiceController.isRunning) activateAssistant()
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(300)
                                    if (VoiceController.isRunning) {
                                        VoiceController.notifyRequest(
                                            com.jarvis.core.common.model.UserRequest(latestRequest),
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
            container.addView(card)
        }
    }

    private fun setupControlCenter(screen: View) {
        screen.findViewById<View>(R.id.tileMemory)?.setOnClickListener { openControlSecondary("memory") }
        screen.findViewById<View>(R.id.tileCommands)?.setOnClickListener { openControlSecondary("commands") }
        screen.findViewById<View>(R.id.tileAutomations)?.setOnClickListener { openControlSecondary("automations") }
        screen.findViewById<View>(R.id.tileCompanion)?.setOnClickListener { openControlSecondary("companion") }
        screen.findViewById<View>(R.id.tileModels)?.setOnClickListener { openControlSecondary("models") }
        screen.findViewById<View>(R.id.tileDeveloper)?.setOnClickListener {
            startActivity(Intent(this, TestConsoleActivity::class.java))
        }
        screen.findViewById<TextView>(R.id.btnOpenAdvancedSettings)?.setOnClickListener {
            openControlSecondary("advanced")
        }
        screen.findViewById<TextView>(R.id.btnOpenUsageGuide)?.setOnClickListener {
            startActivity(Intent(this, UsageGuideActivity::class.java))
        }
        screen.findViewById<TextView>(R.id.btnSwitchModern)?.setOnClickListener {
            UiMode.switchTo(this, com.jarvis.core.startup.UiStyle.MODERN)
        }

        val ctx = this
        screen.findViewById<JarvisToggle>(R.id.toggleAdaptive)?.apply {
            isChecked = JarvisSettings.adaptiveListening(ctx)
            onCheckedChanged = { JarvisSettings.prefs(ctx).edit().putBoolean("adaptive_listening", it).apply() }
        }
        screen.findViewById<JarvisToggle>(R.id.toggleLowPower)?.apply {
            isChecked = JarvisSettings.lowPowerMode(ctx)
            onCheckedChanged = { JarvisSettings.setLowPowerMode(ctx, it) }
        }
        screen.findViewById<JarvisToggle>(R.id.toggleSensitive)?.apply {
            isChecked = JarvisSettings.confirmSensitive(ctx)
            onCheckedChanged = { JarvisSettings.prefs(ctx).edit().putBoolean("confirm_sensitive", it).apply() }
        }

        screen.findViewById<TextView>(R.id.statWake)?.text =
            if (JarvisSettings.wakeEnabled(ctx)) "READY" else "OFF"
        screen.findViewById<TextView>(R.id.statAsr)?.text = JarvisSettings.asrMode(ctx)
        screen.findViewById<TextView>(R.id.statTts)?.text = JarvisSettings.ttsMode(ctx)

        val personaChips = listOf(
            screen.findViewById<TextView>(R.id.personaClassic),
            screen.findViewById<TextView>(R.id.personaFriendly),
            screen.findViewById<TextView>(R.id.personaMinimal),
        )
        val personaNames = listOf("CLASSIC", "FRIENDLY", "MINIMAL")
        val personaLabel = screen.findViewById<TextView>(R.id.personaCurrentLabel)
        fun renderPersona() {
            val cur = JarvisSettings.persona(ctx)
            personaLabel?.text = "JARVIS ${cur.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }}"
            personaChips.forEachIndexed { i, chip ->
                chip?.background = ContextCompat.getDrawable(
                    this,
                    if (personaNames[i] == cur) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
            }
        }
        renderPersona()
        personaChips.forEachIndexed { i, chip ->
            chip?.setOnClickListener {
                JarvisSettings.setPersona(ctx, personaNames[i])
                renderPersona()
                BrainGraph.invalidateChatLlm()
                ActivityFeed.push("Персона", personaNames[i], ActivityFeed.Item.Kind.SYSTEM)
                android.widget.Toast.makeText(
                    this,
                    "Характер: ${personaNames[i]}. Новый стиль применится к следующим ответам.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val chatProfiles = listOf("FAST", "BALANCED", "QUALITY")
        val chatChips = listOf(
            screen.findViewById<TextView>(R.id.chatProfileFast),
            screen.findViewById<TextView>(R.id.chatProfileBalanced),
            screen.findViewById<TextView>(R.id.chatProfileQuality),
        )
        val chatHint = screen.findViewById<TextView>(R.id.chatProfileHint)
        fun chatHintText(p: String): String = when (p) {
            "FAST" -> "Быстро · ~2–4 с"
            "QUALITY" -> "Качество · ~4–7 с"
            else -> "Баланс · 3–5 с"
        }
        fun renderChat() {
            val cur = JarvisSettings.chatProfile(ctx)
            chatHint?.text = chatHintText(cur)
            chatChips.forEachIndexed { i, chip ->
                chip?.background = ContextCompat.getDrawable(
                    this,
                    if (chatProfiles[i] == cur) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
            }
        }
        renderChat()
        chatChips.forEachIndexed { i, chip ->
            chip?.setOnClickListener {
                JarvisSettings.setChatProfile(ctx, chatProfiles[i])
                renderChat()
                BrainGraph.invalidateChatLlm()
                BrainGraph.warmChatLlm(this)
            }
        }

        val themeChips = listOf(
            screen.findViewById<TextView>(R.id.themeClassic),
            screen.findViewById<TextView>(R.id.themeStealth),
            screen.findViewById<TextView>(R.id.themeArc),
            screen.findViewById<TextView>(R.id.themeLight),
        )
        val themeNames = listOf("CLASSIC", "STEALTH", "ARC", "LIGHT")
        fun renderTheme() {
            val cur = JarvisSettings.theme(ctx)
            themeChips.forEachIndexed { i, chip ->
                chip?.background = ContextCompat.getDrawable(
                    this,
                    if (themeNames[i] == cur) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
            }
            screen.findViewById<TextView>(R.id.themeTag)?.text = "Активна: $cur · применяется ко всем поверхностям"
        }
        renderTheme()
        themeChips.forEachIndexed { i, chip ->
            chip?.setOnClickListener {
                JarvisSettings.setTheme(ctx, themeNames[i])
                ActivityFeed.push("Тема", themeNames[i], ActivityFeed.Item.Kind.SYSTEM)
                ThemeApplier.applyToActivity(this)
                ThemeApplier.applyToScreen(screenContainer)
                buildBottomNavigation()
                renderTheme()
                // Full recreate so XML drawables/shell also refresh
                ThemeApplier.recreateWithTheme(this)
            }
        }
    }

    private fun setupAutomationsScreen(screen: View) {
        screen.findViewById<TextView>(R.id.btnBackToControl)?.visibility = View.GONE
        val container = screen.findViewById<LinearLayout>(R.id.routinesContainer) ?: return
        val preview = screen.findViewById<TextView>(R.id.routinePreview)

        fun ensureSeed() {
            BrainGraph.build(this)
            val store = BrainGraph.routineStore() ?: return
            if (store.size() == 0) {
                val saved = RoutinePersistence.load(this)
                if (saved.isNotEmpty()) {
                    saved.forEach { runCatching { store.save(it) } }
                } else if (!RoutinePersistence.seeded(this)) {
                    listOf(
                        "focus" to ("Протокол Фокус" to "Режим не беспокоить и рабочие инструменты"),
                        "brief" to ("Утренний брифинг" to "Календарь, заряд и задачи из памяти"),
                        "power" to ("Энергосбережение ИИ" to "Ниже 20% перейти на лёгкую модель"),
                        "road" to ("В дороге" to "Hands-free и повышенный VAD"),
                    ).forEach { (id, pair) ->
                        runCatching {
                            store.save(RoutinePersistence.stub("routine-$id", pair.first, pair.second, com.jarvis.core.routines.RoutineStatus.SAVED))
                        }
                    }
                    RoutinePersistence.markSeeded(this)
                }
            }
            RoutinePersistence.save(this, store.all())
        }

        fun render() {
            container.removeAllViews()
            BrainGraph.build(this)
            val store = BrainGraph.routineStore()
            val all = store?.all().orEmpty()
            if (all.isEmpty()) {
                container.addView(
                    TextView(this).apply {
                        text = "Протоколов пока нет."
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_muted))
                        textSize = 12f
                    },
                )
                preview?.text = "TRIGGER → ACTION → CONFIRM"
                return
            }
            all.forEach { routine ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                    setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.bottomMargin = dp2px(8)
                    layoutParams = lp
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(
                    TextView(this).apply {
                        text = routine.title
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_text))
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    },
                )
                info.addView(
                    TextView(this).apply {
                        text = routine.naturalLanguage.take(80)
                        setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_muted))
                        textSize = 10f
                        setPadding(0, dp2px(3), 0, 0)
                    },
                )
                row.addView(info)
                val run = TextView(this).apply {
                    text = "ПУСК"
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_fui_active)
                    setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.marginEnd = dp2px(8)
                    layoutParams = lp
                    setOnClickListener {
                        val steps = routine.plan.steps.joinToString("\n") {
                            "• ${it.toolId ?: it.displaySummary}"
                        }.ifBlank { "• ${routine.naturalLanguage}" }
                        preview?.text = "TRIGGER  ${routine.title}\nACTION\n$steps\nCONFIRM  запуск"
                        dispatchAssistantPrompt(routine.naturalLanguage)
                        preview?.append("\nSTATUS  команда отправлена в пайплайн")
                    }
                }
                row.addView(run)
                val enabled = routine.status == com.jarvis.core.routines.RoutineStatus.SAVED
                val toggle = JarvisToggle(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp2px(48), dp2px(28))
                    isChecked = enabled
                    onCheckedChanged = { on ->
                        if (on) {
                            store?.save(routine.copy(status = com.jarvis.core.routines.RoutineStatus.SAVED))
                        } else {
                            store?.disable(routine.id)
                        }
                        store?.let { RoutinePersistence.save(this@DashboardActivity, it.all()) }
                        render()
                    }
                }
                row.addView(toggle)
                row.setOnClickListener {
                    val steps = routine.plan.steps.joinToString("\n") {
                        "• ${it.toolId ?: it.displaySummary}"
                    }.ifBlank { "• ${routine.naturalLanguage}" }
                    preview?.text = "TRIGGER  ${routine.title}\nACTION\n$steps\nCONFIRM  ${routine.status.name}"
                }
                container.addView(row)
            }
        }

        screen.findViewById<TextView>(R.id.btnCreateRoutine)?.setOnClickListener {
            val nameInput = android.widget.EditText(this).apply { hint = "Название" }
            val triggers = arrayOf("По фразе", "Утро", "Батарея ниже 20%", "В дороге")
            val actions = arrayOf("Режим фокуса", "Утренний брифинг", "Лёгкая модель", "Hands-free")
            var trigger = triggers[0]
            var action = actions[0]
            val triggerView = TextView(this).apply {
                text = "ТРИГГЕР  $trigger"
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                setPadding(0, dp2px(12), 0, dp2px(8))
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@DashboardActivity)
                        .setTitle("Триггер")
                        .setItems(triggers) { _, which ->
                            trigger = triggers[which]
                            text = "ТРИГГЕР  $trigger"
                        }
                        .show()
                }
            }
            val actionView = TextView(this).apply {
                text = "ДЕЙСТВИЕ  $action"
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                setPadding(0, dp2px(8), 0, dp2px(8))
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@DashboardActivity)
                        .setTitle("Действие")
                        .setItems(actions) { _, which ->
                            action = actions[which]
                            text = "ДЕЙСТВИЕ  $action"
                        }
                        .show()
                }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8))
                addView(nameInput)
                addView(triggerView)
                addView(actionView)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Новый протокол")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Создать") { _, _ ->
                    val title = nameInput.text.toString().trim().ifBlank { action }
                    val nl = "$trigger: $action"
                    BrainGraph.build(this)
                    val store = BrainGraph.routineStore()
                    val saved = RoutinePersistence.stub(
                        "routine-ui-${System.currentTimeMillis()}",
                        title,
                        nl,
                        com.jarvis.core.routines.RoutineStatus.SAVED,
                    )
                    runCatching { store?.save(saved) }
                    store?.let { RoutinePersistence.save(this, it.all()) }
                    preview?.text = "TRIGGER  $trigger\nACTION  $action\nCONFIRM  сохранено"
                    render()
                }
                .show()
        }
        ensureSeed()
        render()
    }

    /** Память: список фактов из долгой памяти + поиск + удаление. */
    private fun setupMemoryScreen(screen: View) {
        val search = screen.findViewById<TextView>(R.id.memorySearch)
        val container = screen.findViewById<LinearLayout>(R.id.factsContainer)
        val clearBtn = screen.findViewById<TextView>(R.id.clearMemoryButton)
        val clearChatBtn = screen.findViewById<TextView>(R.id.clearChatHistoryButton)
        val addBtn = screen.findViewById<TextView>(R.id.btnAddMemoryFact)
        val modelsStatus = screen.findViewById<TextView>(R.id.memoryModelsStatus)
        val openModels = screen.findViewById<TextView>(R.id.btnOpenModelsFromMemory)

        fun renderModelsHint() {
            val mm = ModelManager.get(this)
            mm.refreshAll()
            val ready = ModelCatalog.ALL.count { mm.status(it.id).state == ModelManager.State.READY }
            modelsStatus?.text = if (ready == 0) {
                "Модели ещё не скачаны. Для быстрого диалога скачай «Qwen3 0.6B», плюс FunctionGemma + TTS/ASR."
            } else {
                "Готово моделей: $ready / ${ModelCatalog.ALL.size}. Недостающие — ЕЩЁ → Модели (для голоса: Qwen3 0.6B)."
            }
        }

        fun render(filter: String = "") {
            val facts = runCatching { BrainGraph.memory(this).all() }
                .getOrDefault(emptyList())
                .filter { memoryCategory == null || it.category == memoryCategory }
                .filter { filter.isBlank() || it.text.contains(filter, ignoreCase = true) }
            val total = runCatching { BrainGraph.memory(this).count() }.getOrDefault(facts.size)
            screen.findViewById<TextView>(R.id.memSlots)?.text =
                "MEMORY SLOTS: $total / 50 · CONTEXT WEIGHT: ${if (total < 40) "OK" else "HIGH"}"
            container.removeAllViews()
            if (facts.isEmpty()) {
                val empty = TextView(this).apply {
                    text = if (filter.isBlank()) {
                        "Фактов пока нет.\nСкажи: «джарвис, запомни: мой день рождения 15 марта»\nили добавь вручную."
                    } else {
                        "Ничего не найдено."
                    }
                    setTextColor(Color.parseColor("#9DA5AE"))
                    textSize = 12f
                }
                container.addView(empty)
                return
            }
            facts.forEach { fact ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp2px(12)
                    layoutParams = lp
                }
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val textView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = fact.text
                    setTextColor(Color.parseColor("#F4F0E7"))
                    textSize = 12f
                }
                val delView = TextView(this).apply {
                    text = "удалить"
                    setTextColor(Color.parseColor("#E46B58"))
                    textSize = 10f
                    setPadding(dp2px(8), dp2px(4), 0, dp2px(4))
                    setOnClickListener {
                        runCatching { BrainGraph.memory(this@DashboardActivity).remove(fact.id) }
                        render(filter)
                    }
                }
                val editView = TextView(this).apply {
                    text = "править"
                    setTextColor(Color.parseColor("#62E9F4"))
                    textSize = 10f
                    setPadding(dp2px(8), dp2px(4), 0, dp2px(4))
                    setOnClickListener {
                        val input = android.widget.EditText(this@DashboardActivity).apply { setText(fact.text) }
                        androidx.appcompat.app.AlertDialog.Builder(this@DashboardActivity)
                            .setTitle("Факт")
                            .setView(input)
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Сохранить") { _, _ ->
                                val next = input.text.toString().trim()
                                if (next.isNotBlank()) {
                                    BrainGraph.memory(this@DashboardActivity).remove(fact.id)
                                    BrainGraph.memory(this@DashboardActivity).add(next, fact.category, fact.source)
                                    render(filter)
                                }
                            }
                            .show()
                    }
                }
                line.addView(textView)
                line.addView(editView)
                line.addView(delView)
                val metaView = TextView(this).apply {
                    text = "${fact.source} · ${java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(fact.createdAt))}"
                    setTextColor(Color.parseColor("#626B76"))
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp2px(3)
                    layoutParams = lp
                }
                row.addView(line)
                row.addView(metaView)
                container.addView(row)
                container.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#2B333D"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(1))
                })
            }
        }

        search.setOnClickListener {
            val input = android.widget.EditText(this).apply { hint = "Поиск в памяти…" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Поиск")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Найти") { _, _ -> render(input.text.toString().trim()) }
                .show()
        }
        addBtn?.setOnClickListener {
            val input = android.widget.EditText(this).apply { hint = "Факт о тебе…" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Добавить в память")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить") { _, _ ->
                    val t = input.text.toString().trim()
                    if (t.isNotEmpty()) {
                        runCatching {
                            BrainGraph.memory(this).add(t, category = "user", source = "manual")
                        }
                        render()
                    }
                }
                .show()
        }
        openModels?.setOnClickListener {
            openControlSecondary("models")
        }
        // «Стереть все факты»: та же очистка, но уже поверх нового хранилища
        // (FactsRepository), поэтому удаляются записи из jarvis_core.db.
        clearBtn.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.memory_clear_facts_title)
                .setMessage(R.string.memory_clear_facts_message)
                .setNegativeButton(R.string.memory_confirm_no, null)
                .setPositiveButton(R.string.memory_confirm_yes) { _, _ ->
                    runCatching { BrainGraph.memory(this).clear() }
                    render()
                }
                .show()
        }
        // «Стереть историю чата»: вся conversation_messages (включая keep=1),
        // RAM-пузыри и текущий ConversationContext.
        clearChatBtn?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.memory_clear_chat_title)
                .setMessage(R.string.memory_clear_chat_message)
                .setNegativeButton(R.string.memory_confirm_no, null)
                .setPositiveButton(R.string.memory_confirm_yes) { _, _ ->
                    runCatching { com.jarvis.core.storage.ConversationJournal.clearAll() }
                    ConversationStore.clear()
                    runCatching { BrainGraph.clearDialogueHistory() }
                }
                .show()
        }
        fun paintMemChips() {
            val chips = listOf(
                R.id.memChipAll to null,
                R.id.memChipProfile to "profile",
                R.id.memChipHabits to "habit",
                R.id.memChipProjects to "project",
                R.id.memChipNotes to "note",
            )
            chips.forEach { (id, cat) ->
                screen.findViewById<TextView>(id)?.apply {
                    val on = memoryCategory == cat
                    setTextColor(ContextCompat.getColor(this@DashboardActivity, if (on) R.color.jarvis_cyan else R.color.jarvis_muted))
                    background = ContextCompat.getDrawable(this@DashboardActivity, if (on) R.drawable.bg_fui_active else R.drawable.bg_hud_panel)
                    setOnClickListener {
                        memoryCategory = cat
                        paintMemChips()
                        render()
                    }
                }
            }
        }
        fun seedDemo() {
            val memory = BrainGraph.memory(this)
            memory.add("Пользователь предпочитает короткие ответы.", "profile", "seed")
            memory.add("Вечером лучше не беспокоить без причины.", "habit", "seed")
            memory.add("Текущий проект — Jarvis for Android.", "project", "seed")
        }
        // Авто-seed демо-фактов отключён: демо-записи больше не попадают в реальную БД
        // памяти при первом открытии экрана. Кнопка btnSeedMemory по-прежнему позволяет
        // добавить их вручную (явное действие пользователя).
        screen.findViewById<TextView>(R.id.btnSeedMemory)?.setOnClickListener {
            seedDemo()
            render()
        }
        paintMemChips()
        renderModelsHint()
        render()
    }

    private fun dp2px(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun wireLiveControls(screen: View) {
        var micOn = true
        fun styleCtl(v: View?, on: Boolean) {
            val color = ContextCompat.getColor(
                this,
                if (on) R.color.jarvis_cyan else R.color.jarvis_muted,
            )
            when (v) {
                is ImageView -> {
                    if (v.id == R.id.liveMic) {
                        v.alpha = if (on) 1f else 0.45f
                        v.setColorFilter(color)
                    } else {
                        v.background = ContextCompat.getDrawable(
                            this,
                            if (on) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                        )
                        v.setColorFilter(color)
                    }
                }
                is TextView -> v.setTextColor(color)
            }
        }
        screen.findViewById<View>(R.id.liveMic)?.setOnClickListener {
            HudFeedback.key(it)
            val idle = assistantState == AssistantState.OFFLINE || assistantState == AssistantState.ERROR
            if (idle) {
                activateAssistant(liveMode = true)
                return@setOnClickListener
            }
            micOn = !micOn
            styleCtl(it, micOn)
            getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
                .edit().putBoolean("live_mic_on", micOn).apply()
            android.widget.Toast.makeText(
                this,
                if (micOn) "Микрофон включён" else "Микрофон приглушён",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        var camOn = false
        screen.findViewById<View>(R.id.liveCam)?.setOnClickListener {
            HudFeedback.key(it)
            camOn = !camOn
            styleCtl(it, camOn)
            if (!camOn) {
                setLiveViewfinder(false)
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                setLiveViewfinder(true)
            } else {
                pendingViewfinder = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        var screenOn = false
        screen.findViewById<View>(R.id.liveScreen)?.setOnClickListener {
            HudFeedback.key(it)
            screenOn = !screenOn
            liveScreenLinked = screenOn
            styleCtl(it, screenOn)
            screen.findViewById<LinearLayout>(R.id.liveShareCard)?.visibility =
                if (screenOn) VISIBLE else GONE
            if (screenOn) {
                android.widget.Toast.makeText(
                    this,
                    "Метка экрана включена. Снимок кадра не снимается.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        screen.findViewById<View>(R.id.liveShareStop)?.setOnClickListener {
            screenOn = false
            liveScreenLinked = false
            styleCtl(screen.findViewById(R.id.liveScreen), false)
            screen.findViewById<LinearLayout>(R.id.liveShareCard)?.visibility = GONE
        }

        var capsOn = true
        screen.findViewById<View>(R.id.liveCaps)?.setOnClickListener {
            HudFeedback.key(it)
            capsOn = !capsOn
            styleCtl(it, capsOn)
            val panelAlpha = if (capsOn) 1f else 0.35f
            screen.findViewById<TextView>(R.id.liveTranscript)?.alpha = panelAlpha
            screen.findViewById<TextView>(R.id.liveTranscriptSaid)?.alpha = panelAlpha
        }
        styleCtl(screen.findViewById(R.id.liveMic), true)
        styleCtl(screen.findViewById(R.id.liveCaps), true)
    }

    private var liveTimerStartMs = 0L
    private val liveTimerHandler = Handler(Looper.getMainLooper())
    private val liveTimerRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - liveTimerStartMs
            val secs = (elapsed / 1000).toInt()
            val mm = "%02d".format(secs / 60)
            val ss = "%02d".format(secs % 60)
            screenContainer.findViewById<TextView>(R.id.liveTimer)?.text = "$mm:$ss"
            liveTimerHandler.postDelayed(this, 1000)
        }
    }

    private fun startLiveTimer(screen: View) {
        if (VoiceController.isLiveSession) {
            liveTimerStartMs = SystemClock.elapsedRealtime()
            liveTimerHandler.removeCallbacks(liveTimerRunnable)
            liveTimerHandler.postDelayed(liveTimerRunnable, 0)
        }
    }

    override fun onDestroy() {
        liveTimerHandler.removeCallbacks(liveTimerRunnable)
        super.onDestroy()
    }

    private fun renderAssistantState(state: AssistantState) {
        assistantState = state
        val themeName = JarvisSettings.theme(this)
        findViewById<TextView>(R.id.statusTitle)?.text =
            screenTitle(currentScreenIndex)

        // --- HOME: CoreView + state label + glyph ---
        val palette = ThemeApplier.palette(themeName)
        val c = CoreTheme.forState(state, palette.cyan)
        val orbAlpha = when (state) {
            AssistantState.ERROR -> 0.55f
            AssistantState.THINKING, AssistantState.EXECUTING -> 0.78f
            AssistantState.SPEAKING -> 1.0f
            AssistantState.LISTENING -> 1.0f
            else -> 0.9f
        }

        screenContainer.findViewById<TextView>(R.id.assistantState)?.apply {
            text = when (state) {
                AssistantState.OFFLINE, AssistantState.ERROR -> "● VOICE SERVICE: OFFLINE (TAP MIC TO START)"
                AssistantState.STARTING -> "● VOICE SERVICE: STARTING"
                else -> "● VOICE SERVICE: ACTIVE"
            }
            setTextColor(c.core)
        }
        screenContainer.findViewById<TextView>(R.id.assistantStateDetail)?.text = stateDetail(state)
        screenContainer.findViewById<ReactorCoreView>(R.id.homeOrb)?.apply {
            this.state = state
            this.accent = palette.cyan
            this.hot = palette.primary
            alpha = orbAlpha
        }
        screenContainer.findViewById<EqualizerView>(R.id.homeEq)?.apply {
            active = state == AssistantState.LISTENING || state == AssistantState.SPEAKING
            this.accent = palette.cyan
        }
        val pipes = listOf(
            R.id.pipeWake to (state == AssistantState.IDLE || state == AssistantState.READY || state == AssistantState.OFFLINE),
            R.id.pipeAsr to (state == AssistantState.LISTENING),
            R.id.pipeRouter to (state == AssistantState.THINKING),
            R.id.pipeLlm to (state == AssistantState.THINKING || state == AssistantState.EXECUTING),
            R.id.pipeTts to (state == AssistantState.SPEAKING),
        )
        val wakeHot = JarvisSettings.wakeEnabled(this) &&
            (state == AssistantState.OFFLINE || state == AssistantState.IDLE || state == AssistantState.READY)
        pipes.forEach { (id, on) ->
            val hot = on || (id == R.id.pipeWake && wakeHot)
            screenContainer.findViewById<TextView>(id)?.apply {
                setTextColor(ContextCompat.getColor(this@DashboardActivity, if (hot) R.color.jarvis_cyan else R.color.jarvis_faint))
                background = ContextCompat.getDrawable(
                    this@DashboardActivity,
                    if (hot) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
            }
        }
        screenContainer.findViewById<View>(R.id.activateAssistant)?.isEnabled =
            state != AssistantState.STARTING

        // --- LIVE: CoreView + state label + glyph + buttons ---
        screenContainer.findViewById<ConsciousnessView>(R.id.liveOrb)?.apply {
            this.state = state
            alpha = orbAlpha
        }
        screenContainer.findViewById<TextView>(R.id.liveState)?.apply {
            text = when (state) {
                AssistantState.LISTENING -> "LISTENING"
                AssistantState.THINKING, AssistantState.EXECUTING -> "PROCESSING"
                AssistantState.SPEAKING -> "SPEAKING"
                AssistantState.STARTING -> "CONNECTING"
                AssistantState.ERROR -> "LINK ERROR"
                else -> "DUPLEX STANDBY"
            }
            setTextColor(c.core)
        }
        screenContainer.findViewById<TextView>(R.id.liveActivate)?.apply {
            text = when (state) {
                AssistantState.OFFLINE, AssistantState.ERROR -> "СТАРТ"
                AssistantState.STARTING -> "ЖДУ"
                else -> "LIVE"
            }
            isEnabled = state != AssistantState.STARTING
            alpha = if (state == AssistantState.STARTING) 0.6f else 1f
        }
        screenContainer.findViewById<View>(R.id.liveStop)?.isEnabled =
            state != AssistantState.OFFLINE && state != AssistantState.STARTING
        screenContainer.findViewById<TextView>(R.id.liveVoiceBadge)?.text = when (state) {
            AssistantState.LISTENING -> "LISTEN"
            AssistantState.SPEAKING -> "SPEAK"
            AssistantState.THINKING, AssistantState.EXECUTING -> "THINK"
            AssistantState.OFFLINE, AssistantState.ERROR -> "IDLE"
            else -> "VOICE"
        }    }

    private var pendingLiveMode = false

    private var liveScreenLinked = false

    private fun setLiveViewfinder(show: Boolean) {
        val finder = screenContainer.findViewById<View>(R.id.liveViewfinder) ?: return
        finder.visibility = if (show) View.VISIBLE else View.GONE
        screenContainer.findViewById<TextView>(R.id.liveCamThumb)?.visibility = View.GONE
    }

    private fun flashScreenBadge() {
        val badge = screenContainer.findViewById<View>(R.id.liveShareCard) ?: return
        if (badge.visibility != View.VISIBLE) return
        badge.animate().alpha(0.25f).setDuration(80).withEndAction {
            badge.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun activateAssistant(liveMode: Boolean = false) {
        HudFeedback.beep(
            this,
            if (liveMode) android.media.ToneGenerator.TONE_PROP_BEEP2 else android.media.ToneGenerator.TONE_PROP_BEEP,
        )
        pendingLiveMode = liveMode
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotificationPermissionOrStart(liveMode)
    }

    private fun requestNotificationPermissionOrStart(liveMode: Boolean = pendingLiveMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (liveMode) VoiceController.startLive(this) else VoiceController.start(this)
        }
    }

    private fun handleAssistantEvent(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.TranscriptUpdated -> latestTranscript = event.text
            is AssistantEvent.RequestReceived -> {
                latestRequest = event.text
                ConversationStore.pushUser(event.text)
                ActivityFeed.push("Запрос", event.text, ActivityFeed.Item.Kind.VOICE)
                if (liveScreenLinked) flashScreenBadge()
            }
            is AssistantEvent.ResponseGenerated -> {
                latestResponse = event.text
                ConversationStore.pushAi(
                    event.text,
                    badge = "DONE",
                    plan = latestIntent.takeIf { it.isNotBlank() },
                )
                ActivityFeed.push("Ответ JARVIS", event.text.take(120), ActivityFeed.Item.Kind.VOICE)
            }
            is AssistantEvent.IntentResolved -> {
                latestIntent = "${event.intent.type} · ${(event.intent.confidence * 100).toInt()}%"
                ActivityFeed.push(
                    "Intent",
                    latestIntent,
                    ActivityFeed.Item.Kind.SYSTEM,
                )
            }
            is AssistantEvent.ToolExecuted -> {
                latestTool = "${event.result.toolId} · ${event.result.status} · ${event.result.durationMs} ms"
                val pending = event.result.status == com.jarvis.core.common.model.ToolResultStatus.NEEDS_CONFIRMATION
                val failed = event.result.status == com.jarvis.core.common.model.ToolResultStatus.FAILED ||
                    event.result.status == com.jarvis.core.common.model.ToolResultStatus.DENIED ||
                    event.result.status == com.jarvis.core.common.model.ToolResultStatus.UNAVAILABLE
                ActivityFeed.push(
                    "Tool · ${event.result.toolId}",
                    event.result.message ?: "${event.result.status} · ${event.result.durationMs} ms",
                    if (pending) ActivityFeed.Item.Kind.CONFIRM else ActivityFeed.Item.Kind.TOOL,
                    pendingConfirm = pending,
                    failed = failed,
                )
                if (pending) {
                    showHudConfirm(
                        event.result.toolId,
                        event.result.message ?: event.result.toolId,
                    )
                }
            }
            AssistantEvent.WakeDetected -> {
                latestResponse = "Слушаю команду…"
                ActivityFeed.push("Wake", "Вейк-ворд обнаружен", ActivityFeed.Item.Kind.VOICE)
            }
            else -> return
        }
        renderVoiceContent()
        if (currentScreenIndex == TAB_ASSISTANT) {
            screenContainer.findViewById<LinearLayout>(R.id.assistantThread)?.let { renderAssistantThread(it) }
        }
        if (currentScreenIndex == TAB_ACTIVITY) {
            screenContainer.findViewById<LinearLayout>(R.id.activityFeedContainer)?.let { renderActivityFeed(it) }
            updateJournalStats()
        }
    }

    private fun renderVoiceContent() {
        // HOME
        screenContainer.findViewById<TextView>(R.id.currentTranscript)?.text =
            latestTranscript.ifBlank { "Ожидание голосовой команды…" }
        screenContainer.findViewById<TextView>(R.id.currentResponse)?.text =
            latestResponse.ifBlank { "После обработки команды здесь появится ответ. Текст можно продолжить в Ассистенте." }
        // LIVE
        screenContainer.findViewById<TextView>(R.id.liveTranscript)?.text =
            latestResponse.ifBlank { "Дуплексный канал. Говорите без вейк-ворда." }
        screenContainer.findViewById<TextView>(R.id.liveTranscriptSaid)?.text =
            latestRequest.ifBlank { "ожидание сессии" }.let { "«$it»" }
        val toolLine = latestTool.ifBlank { "" }
        screenContainer.findViewById<TextView>(R.id.liveToolChip)?.text =
            if (toolLine.isBlank()) "" else "[ TOOL: $toolLine ]"
        screenContainer.findViewById<TextView>(R.id.liveIntent)?.text = latestIntent
        screenContainer.findViewById<TextView>(R.id.liveTool)?.text = latestTool
    }

    private fun stateLabel(state: AssistantState): String = when (state) {
        AssistantState.OFFLINE -> "● OFFLINE"
        AssistantState.STARTING -> "● STARTING"
        AssistantState.IDLE, AssistantState.READY -> "● READY"
        AssistantState.LISTENING -> "● LISTENING"
        AssistantState.THINKING -> "● THINKING"
        AssistantState.EXECUTING -> "● EXECUTING"
        AssistantState.SPEAKING -> "● SPEAKING"
        AssistantState.ERROR -> "● ERROR"
    }

    private fun stateDetail(state: AssistantState): String = when (state) {
        AssistantState.OFFLINE -> "Фоновый сервис не запущен."
        AssistantState.STARTING -> "Запускаю локальное ядро."
        AssistantState.IDLE, AssistantState.READY -> "Готов к голосовой команде."
        AssistantState.LISTENING -> "Слушаю голосовую команду."
        AssistantState.THINKING -> "Обрабатываю запрос."
        AssistantState.EXECUTING -> "Выполняю действие через инструмент."
        AssistantState.SPEAKING -> "Озвучиваю ответ."
        AssistantState.ERROR -> "Требуется внимание к состоянию сервиса."
    }

    private var torchOn = false
    private val uiTimerHandler = Handler(Looper.getMainLooper())

    private fun setTorch(on: Boolean): Boolean {
        return runCatching {
            val cm = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cam ->
                cm.getCameraCharacteristics(cam)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            torchOn = on
            true
        }.getOrDefault(false)
    }

    private fun toolFlashRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp2px(8)
        layoutParams = lp
        addView(TextView(this@DashboardActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = if (torchOn) "ФОНАРИК ВКЛ" else "ФОНАРИК ВЫКЛ"
            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        addView(JarvisToggle(this@DashboardActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(48), dp2px(28))
            var guard = false
            isChecked = torchOn
            onCheckedChanged = torch@{ on ->
                if (guard) return@torch
                if (ContextCompat.checkSelfPermission(this@DashboardActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingTorch = on
                    guard = true
                    isChecked = torchOn
                    guard = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return@torch
                }
                val ok = setTorch(on)
                if (!ok) {
                    guard = true
                    isChecked = torchOn
                    guard = false
                    android.widget.Toast.makeText(this@DashboardActivity, "Фонарик недоступен", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun toolVolumeSlider(): android.widget.SeekBar {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        return android.widget.SeekBar(this).apply {
            this.max = 100
            progress = cur * 100 / max
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E9F4"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp2px(8)
            layoutParams = lp
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    seekBar?.let { HudFeedback.tick(it) }
                    audio.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        (value * max / 100f).toInt().coerceIn(0, max),
                        0,
                    )
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })
        }
    }

    private fun toolBrightnessBlock(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp2px(8)
        layoutParams = lp
        if (!android.provider.Settings.System.canWrite(this@DashboardActivity)) {
            addView(TextView(this@DashboardActivity).apply {
                text = "РАЗРЕШИТЬ ЯРКОСТЬ"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8))
                setOnClickListener {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                }
            })
        }
        val current = runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        addView(android.widget.SeekBar(this@DashboardActivity).apply {
            this.max = 100
            progress = (current * 100 / 255).coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E9F4"))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    seekBar?.let { HudFeedback.tick(it) }
                    val level = (value * 255 / 100).coerceIn(1, 255)
                    if (android.provider.Settings.System.canWrite(this@DashboardActivity)) {
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            level,
                        )
                    }
                    val attr = window.attributes
                    attr.screenBrightness = level / 255f
                    window.attributes = attr
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })
        })
    }

    private fun toolTimerRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp2px(8)
        layoutParams = lp
        listOf(1, 5, 10).forEachIndexed { index, minutes ->
            addView(TextView(this@DashboardActivity).apply {
                text = "+$minutes МИН"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                setPadding(dp2px(6), dp2px(8), dp2px(6), dp2px(8))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (index < 2) lp.marginEnd = dp2px(8)
                layoutParams = lp
                setOnClickListener {
                    uiTimerHandler.postDelayed({
                        val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                        try {
                            vibrator?.vibrate(
                                android.os.VibrationEffect.createOneShot(
                                    700,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                                ),
                            )
                        } catch (e: SecurityException) {
                            Log.w("Jarvis.Dashboard", "vibrate: нет разрешения VIBRATE", e)
                        } catch (e: Exception) {
                            Log.w("Jarvis.Dashboard", "vibrate failed: ${e.message}", e)
                        }
                        android.widget.Toast.makeText(this@DashboardActivity, "Таймер $minutes мин истёк", android.widget.Toast.LENGTH_LONG).show()
                    }, minutes * 60_000L)
                    android.widget.Toast.makeText(this@DashboardActivity, "Таймер на $minutes мин", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun setupCommandsScreen(screen: android.view.View) {
        val list = screen.findViewById<LinearLayout>(R.id.commandsList) ?: return
        val chips = screen.findViewById<LinearLayout>(R.id.commandsCategoryChips)
        val search = screen.findViewById<TextView>(R.id.commandsSearch)

        fun riskColor(r: AssistantCapabilities.RiskTag): Int = when (r) {
            AssistantCapabilities.RiskTag.SAFE -> Color.parseColor("#62D9DF")
            AssistantCapabilities.RiskTag.ONLINE -> Color.parseColor("#E0AD55")
            AssistantCapabilities.RiskTag.CONFIRM -> Color.parseColor("#E0AD55")
            AssistantCapabilities.RiskTag.SENSITIVE -> Color.parseColor("#E46B58")
        }

        fun renderList() {
            list.removeAllViews()
            val filtered = AssistantCapabilities.ALL.filter { cmd ->
                val catOk = commandsCategory == null || cmd.category == commandsCategory
                val q = commandsFilter
                val textOk = q.isBlank() ||
                    cmd.title.contains(q, true) ||
                    cmd.description.contains(q, true) ||
                    cmd.examples.any { it.contains(q, true) } ||
                    cmd.category.contains(q, true)
                catOk && textOk
            }
            if (filtered.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "Ничего не найдено."
                    setTextColor(Color.parseColor("#9DA5AE"))
                    textSize = 12f
                })
                return
            }
            var lastCat = ""
            for (cmd in filtered) {
                if (cmd.category != lastCat) {
                    lastCat = cmd.category
                    list.addView(TextView(this).apply {
                        text = cmd.category.uppercase()
                        setTextColor(Color.parseColor("#626B76"))
                        textSize = 11f
                        letterSpacing = 0.06f
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        lp.topMargin = dp2px(14)
                        lp.bottomMargin = dp2px(8)
                        layoutParams = lp
                    })
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                    setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.bottomMargin = dp2px(10)
                    layoutParams = lp
                }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = cmd.title
                    setTextColor(Color.parseColor("#F4F0E7"))
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                head.addView(TextView(this).apply {
                    text = cmd.risk.name
                    setTextColor(riskColor(cmd.risk))
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(dp2px(8), dp2px(3), dp2px(8), dp2px(3))
                    background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_tag_cyan)
                })
                card.addView(head)
                card.addView(TextView(this).apply {
                    text = cmd.description + if (cmd.needs.isNotBlank()) "\nНужно: ${cmd.needs}" else ""
                    setTextColor(Color.parseColor("#9DA5AE"))
                    textSize = 12f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.topMargin = dp2px(6)
                    layoutParams = lp
                })
                when (cmd.id) {
                    "flashlight" -> card.addView(toolFlashRow())
                    "volume" -> card.addView(toolVolumeSlider())
                    "brightness" -> card.addView(toolBrightnessBlock())
                    "timer" -> card.addView(toolTimerRow())
                }
                cmd.examples.forEach { ex ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        lp.topMargin = dp2px(8)
                        layoutParams = lp
                    }
                    row.addView(TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "«$ex»"
                        setTextColor(Color.parseColor("#E0AD55"))
                        textSize = 12f
                        setOnClickListener {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("jarvis", ex))
                            android.widget.Toast.makeText(
                                this@DashboardActivity,
                                "Скопировано. Скажи это после активации.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    })
                    row.addView(TextView(this).apply {
                        text = "RUN"
                        setTextColor(Color.parseColor("#62E9F4"))
                        textSize = 11f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setPadding(dp2px(12), dp2px(6), dp2px(12), dp2px(6))
                        background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
                        setOnClickListener { runVoiceCommand(ex) }
                    })
                    card.addView(row)
                }
                list.addView(card)
            }
        }

        fun renderChips() {
            chips?.removeAllViews()
            val cats = listOf(null to "Все") +
                AssistantCapabilities.byCategory().keys.map { it to it }
            cats.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.bottomMargin = dp2px(8)
                    layoutParams = lp
                }
                pair.forEachIndexed { index, (cat, label) ->
                    row.addView(TextView(this).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = 11f
                        minHeight = dp2px(36)
                        setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8))
                        val active = commandsCategory == cat
                        setTextColor(if (active) Color.parseColor("#62E9F4") else Color.parseColor("#8F9BAE"))
                        background = ContextCompat.getDrawable(
                            this@DashboardActivity,
                            if (active) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                        )
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        if (index == 0) lp.marginEnd = dp2px(8)
                        layoutParams = lp
                        setOnClickListener {
                            commandsCategory = cat
                            renderChips()
                            renderList()
                        }
                    })
                }
                if (pair.size == 1) {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    })
                }
                chips?.addView(row)
            }
        }

        search?.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                hint = "Поиск…"
                setText(commandsFilter)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Поиск команд")
                .setView(input)
                .setNegativeButton("Сброс") { _, _ ->
                    commandsFilter = ""
                    search.text = "⌕  Найти команду…"
                    renderList()
                }
                .setPositiveButton("Найти") { _, _ ->
                    commandsFilter = input.text.toString().trim()
                    search.text = if (commandsFilter.isBlank()) {
                        "⌕  Найти команду…"
                    } else {
                        "⌕  $commandsFilter"
                    }
                    renderList()
                }
                .show()
        }
        renderChips()
        renderList()
    }

    private fun runVoiceCommand(phrase: String) {
        val text = phrase.removePrefix("джарвис,").removePrefix("джарвис").trim()
            .ifBlank { phrase }
        if (!VoiceController.isRunning) {
            activateAssistant()
            android.widget.Toast.makeText(
                this,
                "Запускаю ассистента… повтори через секунду или скажи фразу вслух.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        lifecycleScope.launch {
            kotlinx.coroutines.delay(400)
            if (VoiceController.isRunning) {
                VoiceController.notifyRequest(com.jarvis.core.common.model.UserRequest(text))
                showScreen(TAB_HOME)
            }
        }
    }

    private fun setupSettingsToggles(screen: android.view.View) {
        val ctx = this
        val mainPanel = screen.findViewById<View>(R.id.settingsMainPanel)
        val modelsPanel = screen.findViewById<View>(R.id.modelsPanel)
        val companionPanel = screen.findViewById<View>(R.id.companionPanel)
        val tabMain = screen.findViewById<TextView>(R.id.tabSettingsMain)
        val tabModels = screen.findViewById<TextView>(R.id.tabSettingsModels)
        val tabCompanion = screen.findViewById<TextView>(R.id.tabSettingsCompanion)

        fun showSettingsTab(which: String) {
            mainPanel?.visibility = if (which == "main") View.VISIBLE else View.GONE
            modelsPanel?.visibility = if (which == "models") View.VISIBLE else View.GONE
            companionPanel?.visibility = if (which == "companion") View.VISIBLE else View.GONE
            fun style(tab: TextView?, active: Boolean) {
                tab?.background = ContextCompat.getDrawable(
                    this,
                    if (active) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
            }
            style(tabMain, which == "main")
            style(tabModels, which == "models")
            style(tabCompanion, which == "companion")
            if (which == "models") setupModelsPanel(screen)
            if (which == "companion") setupCompanionPanel(screen)
        }
        tabMain?.setOnClickListener { showSettingsTab("main") }
        tabModels?.setOnClickListener { showSettingsTab("models") }
        tabCompanion?.setOnClickListener { showSettingsTab("companion") }
        val initial = pendingSettingsTab ?: "main"
        pendingSettingsTab = null
        showSettingsTab(initial)

        screen.findViewById<TextView>(R.id.btnOpenUsageGuide)?.setOnClickListener {
            startActivity(Intent(this, UsageGuideActivity::class.java))
        }
        screen.findViewById<TextView>(R.id.btnSwitchModern)?.setOnClickListener {
            UiMode.switchTo(this, com.jarvis.core.startup.UiStyle.MODERN)
        }

        screen.findViewById<JarvisToggle>(R.id.toggleHudSfx)?.apply {
            isChecked = JarvisSettings.hudSfx(ctx)
            onCheckedChanged = { JarvisSettings.setHudSfx(ctx, it) }
        }
        screen.findViewById<JarvisToggle>(R.id.toggleAdaptive)?.apply {
            isChecked = JarvisSettings.adaptiveListening(ctx)
            onCheckedChanged = { JarvisSettings.prefs(ctx).edit().putBoolean("adaptive_listening", it).apply() }
        }
        screen.findViewById<JarvisToggle>(R.id.toggleLowPower)?.apply {
            isChecked = JarvisSettings.lowPowerMode(ctx)
            onCheckedChanged = { JarvisSettings.prefs(ctx).edit().putBoolean("low_power", it).apply() }
        }
        screen.findViewById<JarvisToggle>(R.id.toggleSensitive)?.apply {
            isChecked = JarvisSettings.confirmSensitive(ctx)
            onCheckedChanged = { JarvisSettings.prefs(ctx).edit().putBoolean("confirm_sensitive", it).apply() }
        }

        // ── Вейк-ворд: вкл/выкл + чувствительность ──
        screen.findViewById<JarvisToggle>(R.id.toggleWake)?.apply {
            isChecked = JarvisSettings.wakeEnabled(ctx)
            onCheckedChanged = { enabled ->
                JarvisSettings.setWakeEnabled(ctx, enabled)
                // если сервис запущен — применяем сейчас
                if (VoiceController.isRunning) {
                    VoiceController.stop(this@DashboardActivity)
                    VoiceController.start(this@DashboardActivity)
                }
            }
        }

        screen.findViewById<TextView>(R.id.wakeEngineStatus)?.text =
            "Движок: openWakeWord · «Джарвис» (ONNX KWS)"

        val wakeLevels = listOf(0, 1, 2)
        val wakeTags = listOf(
            screen.findViewById<TextView>(R.id.wakeLow),
            screen.findViewById<TextView>(R.id.wakeMedium),
            screen.findViewById<TextView>(R.id.wakeHigh),
        )
        fun renderWakeLevels() {
            val cur = JarvisSettings.wakeSensitivity(ctx)
            wakeTags.forEachIndexed { i, tag ->
                tag?.background = ContextCompat.getDrawable(
                    this@DashboardActivity,
                    if (cur == wakeLevels[i]) R.drawable.bg_fui_active else R.drawable.bg_hud_panel
                )
            }
        }
        renderWakeLevels()
        wakeTags.forEachIndexed { i, tag ->
            tag?.setOnClickListener {
                JarvisSettings.setWakeSensitivity(ctx, wakeLevels[i])
                renderWakeLevels()
                if (VoiceController.isRunning) {
                    VoiceController.stop(this@DashboardActivity)
                    VoiceController.start(this@DashboardActivity)
                }
            }
        }

        // ── Порог уверенности Брейна ──
        val thresholdText = screen.findViewById<TextView>(R.id.brainThreshold)
        val slider = screen.findViewById<JarvisSlider>(R.id.sliderThreshold)
        // Слайдер хранит 0..1; порог живёт в 0.3..0.9 — маппим явно.
        fun thresholdToSlider(t: Float): Float = (t - 0.3f) / 0.6f
        fun sliderToThreshold(s: Float): Float = 0.3f + s * 0.6f
        slider.value = thresholdToSlider(JarvisSettings.brainConfidenceThreshold(ctx))
        thresholdText?.text = "%.2f".format(sliderToThreshold(slider.value))
        slider.onValueChanged = { v ->
            JarvisSettings.setBrainConfidenceThreshold(ctx, sliderToThreshold(v))
            thresholdText?.text = "%.2f".format(sliderToThreshold(v))
        }

        // ── Память ──
        val memoryCount = screen.findViewById<TextView>(R.id.memoryCount)
        fun refreshMemoryCount() {
            val n = runCatching { BrainGraph.memory(this@DashboardActivity).count() }.getOrDefault(0)
            memoryCount?.text = n.toString()
        }
        screen.findViewById<JarvisToggle>(R.id.toggleMemory)?.apply {
            isChecked = JarvisSettings.prefs(ctx).getBoolean("memory_enabled", true)
            onCheckedChanged = { JarvisSettings.prefs(ctx).edit().putBoolean("memory_enabled", it).apply() }
        }
        screen.findViewById<Button>(R.id.btnClearMemory)?.setOnClickListener {
            runCatching { BrainGraph.memory(this@DashboardActivity).clear() }
            refreshMemoryCount()
        }
        refreshMemoryCount()

        val themeTiles = listOf(
            screen.findViewById<android.view.View>(R.id.themeClassic),
            screen.findViewById<android.view.View>(R.id.themeArc),
            screen.findViewById<android.view.View>(R.id.themeStealth),
            screen.findViewById<android.view.View>(R.id.themeLight),
        )
        val themeNames = listOf("CLASSIC", "ARC", "STEALTH", "LIGHT")
        val currentTheme = JarvisSettings.theme(this)
        val themeTag = screen.findViewById<TextView>(R.id.themeTag)
        themeTiles.forEachIndexed { i, tile ->
            tile?.isSelected = themeNames[i] == currentTheme
            tile?.setOnClickListener {
                JarvisSettings.setTheme(this, themeNames[i])
                themeTag?.text = themeNames[i]
                ThemeApplier.recreateWithTheme(this)
            }
        }
        val personaChips = listOf(
            screen.findViewById<TextView>(R.id.personaClassic),
            screen.findViewById<TextView>(R.id.personaFriendly),
            screen.findViewById<TextView>(R.id.personaMinimal),
            screen.findViewById<TextView>(R.id.personaDeveloper),
        )
        val personaNames = listOf("CLASSIC", "FRIENDLY", "MINIMAL", "DEVELOPER")
        val currentPersona = JarvisSettings.persona(this)
        personaChips.forEachIndexed { i, chip ->
            chip?.background = ContextCompat.getDrawable(
                this,
                if (personaNames[i] == currentPersona) R.drawable.bg_fui_active else R.drawable.bg_hud_panel
            )
            chip?.setOnClickListener {
                JarvisSettings.setPersona(this, personaNames[i])
                personaChips.forEachIndexed { j, c ->
                    c?.background = ContextCompat.getDrawable(
                        this,
                        if (j == i) R.drawable.bg_fui_active else R.drawable.bg_hud_panel
                    )
                }
                BrainGraph.invalidateChatLlm()
                android.widget.Toast.makeText(
                    this,
                    "Характер: ${personaNames[i]}",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // ── Профиль диалога LLM ──
        val chatProfiles = listOf("FAST", "BALANCED", "QUALITY")
        val chatChips = listOf(
            screen.findViewById<TextView>(R.id.chatProfileFast),
            screen.findViewById<TextView>(R.id.chatProfileBalanced),
            screen.findViewById<TextView>(R.id.chatProfileQuality),
        )
        val chatHint = screen.findViewById<TextView>(R.id.chatProfileHint)
        fun chatHintText(p: String): String = when (p) {
            "FAST" -> "Быстро: короче ответы, ниже latency (~2–4 с)."
            "QUALITY" -> "Качество: чуть дольше, полнее формулировки (~4–7 с)."
            else -> "Баланс: ~3–5 с, ясные ответы без воды."
        }
        fun renderChatProfile() {
            val cur = JarvisSettings.chatProfile(this)
            chatChips.forEachIndexed { i, chip ->
                chip?.background = ContextCompat.getDrawable(
                    this,
                    if (chatProfiles[i] == cur) R.drawable.bg_fui_active else R.drawable.bg_hud_panel,
                )
            }
            chatHint?.text = chatHintText(cur)
        }
        renderChatProfile()
        chatChips.forEachIndexed { i, chip ->
            chip?.setOnClickListener {
                JarvisSettings.setChatProfile(this, chatProfiles[i])
                renderChatProfile()
                // Перезагрузить sampler/ctx под новый профиль и прогреть.
                BrainGraph.invalidateChatLlm()
                BrainGraph.warmChatLlm(this)
            }
        }
        screen.findViewById<JarvisToggle>(R.id.toggleChatPreload)?.apply {
            isChecked = JarvisSettings.chatPreloadEnabled(ctx)
            onCheckedChanged = { enabled ->
                JarvisSettings.setChatPreloadEnabled(ctx, enabled)
                if (enabled) BrainGraph.preloadChatModel(ctx)
            }
        }

        // ── Cloud LLM ──
        val cloudKeyInput = screen.findViewById<android.widget.EditText>(R.id.cloudApiKeyInput)
        val cloudHint = screen.findViewById<TextView>(R.id.cloudApiKeyHint)
        cloudKeyInput?.setText("")
        fun refreshCloudHint() {
            val set = JarvisSettings.cloudConfigured(this)
            cloudHint?.text = if (set) {
                "Ключ сохранён · модель: ${JarvisSettings.cloudModelForProfile(this)} · " +
                    "preferCloud=${JarvisSettings.preferCloudLlm(this)}"
            } else {
                "Облачный endpoint не задан. Для custom укажите адрес в настройках."
            }
        }
        refreshCloudHint()
        cloudKeyInput?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val key = cloudKeyInput.text?.toString().orEmpty()
                JarvisSettings.setCloudApiKey(this, key)
                BrainGraph.onCloudKeyChanged("custom")
                BrainGraph.invalidate()
                refreshCloudHint()
            }
        }
        screen.findViewById<JarvisToggle>(R.id.togglePreferCloud)?.apply {
            isChecked = JarvisSettings.preferCloudLlm(ctx)
            onCheckedChanged = { enabled ->
                JarvisSettings.setPreferCloudLlm(ctx, enabled)
                BrainGraph.invalidate()
                refreshCloudHint()
            }
        }


        fun bindCloudProviderKey(
            providerId: String,
            inputId: Int,
            statusId: Int,
            saveId: Int,
            clearId: Int,
        ) {
            val input = screen.findViewById<android.widget.EditText>(inputId) ?: return
            val status = screen.findViewById<TextView>(statusId) ?: return
            val store = SecureKeyStore(ctx)
            fun refreshStatus() {
                status.setText(if (store.hasKey(providerId)) R.string.cloud_key_set else R.string.cloud_key_missing)
            }
            input.setText("")
            refreshStatus()
            screen.findViewById<android.widget.Button>(saveId)?.setOnClickListener {
                val clean = com.jarvis.core.llm.CloudKeyValidator.sanitizeKey(input.text?.toString())
                if (com.jarvis.core.llm.CloudKeyValidator.isStubKey(clean)) {
                    android.widget.Toast.makeText(ctx, R.string.cloud_key_rejected, android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                store.setKey(providerId, clean)
                input.setText("")
                refreshStatus()
                BrainGraph.onCloudKeyChanged(providerId)
                BrainGraph.invalidate()
                android.widget.Toast.makeText(ctx, R.string.cloud_key_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
            screen.findViewById<android.widget.Button>(clearId)?.setOnClickListener {
                store.clearKey(providerId)
                input.setText("")
                refreshStatus()
                BrainGraph.onCloudKeyChanged(providerId)
                BrainGraph.invalidate()
                android.widget.Toast.makeText(ctx, R.string.cloud_key_deleted, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        bindCloudProviderKey(
            providerId = "groq",
            inputId = R.id.groqKeyInput,
            statusId = R.id.groqKeyStatus,
            saveId = R.id.groqKeySave,
            clearId = R.id.groqKeyClear,
        )
        bindCloudProviderKey(
            providerId = "gemini",
            inputId = R.id.geminiKeyInput,
            statusId = R.id.geminiKeyStatus,
            saveId = R.id.geminiKeySave,
            clearId = R.id.geminiKeyClear,
        )

        // ── ASR mode (V2-08) ──
        val asrModes = listOf("AUTO", "CLOUD_PREFERRED", "LOCAL_ONLY")
        val asrLabels = listOf("Auto", "Cloud preferred", "Local only")
        val asrSpinner = screen.findViewById<android.widget.Spinner>(R.id.asrModeSpinner)
        val asrHint = screen.findViewById<TextView>(R.id.asrModeHint)
        asrSpinner?.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            asrLabels,
        )
        val curAsr = JarvisSettings.asrMode(this)
        asrSpinner?.setSelection(asrModes.indexOf(curAsr).coerceAtLeast(0))
        fun refreshAsrHint() {
            asrHint?.text =
                "Режим: ${JarvisSettings.asrMode(this)} · " +
                    "provider: ${com.jarvis.app.asr.CloudAsrFactory.cloudConfiguredLabel(this)}"
        }
        refreshAsrHint()
        asrSpinner?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                JarvisSettings.setAsrMode(ctx, asrModes.getOrElse(position) { "AUTO" })
                BrainGraph.invalidate()
                refreshAsrHint()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        screen.findViewById<android.widget.EditText>(R.id.asrCloudBaseUrlInput)?.apply {
            setText(JarvisSettings.asrCloudBaseUrl(ctx))
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    JarvisSettings.setAsrCloudBaseUrl(ctx, text?.toString().orEmpty())
                    BrainGraph.invalidate()
                    refreshAsrHint()
                }
            }
        }
        screen.findViewById<android.widget.EditText>(R.id.asrCloudApiKeyInput)?.apply {
            setText(
                JarvisSettings.asrCloudApiKey(ctx),
            )
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    JarvisSettings.setAsrCloudApiKey(ctx, text?.toString().orEmpty())
                    BrainGraph.invalidate()
                    refreshAsrHint()
                }
            }
        }

        // ── TTS mode (V2-09) ──
        val ttsModes = listOf("AUTO", "CLOUD_PREFERRED", "LOCAL_ONLY")
        val ttsLabels = listOf("Auto", "Cloud preferred", "Local only")
        val ttsSpinner = screen.findViewById<android.widget.Spinner>(R.id.ttsModeSpinner)
        val ttsHint = screen.findViewById<TextView>(R.id.ttsModeHint)
        ttsSpinner?.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ttsLabels,
        )
        ttsSpinner?.setSelection(ttsModes.indexOf(JarvisSettings.ttsMode(this)).coerceAtLeast(0))
        fun refreshTtsHint() {
            ttsHint?.text =
                "Режим: ${JarvisSettings.ttsMode(this)} · " +
                    "provider: ${com.jarvis.app.tts.CloudTtsFactory.cloudConfiguredLabel(this)}"
        }
        refreshTtsHint()
        ttsSpinner?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                JarvisSettings.setTtsMode(ctx, ttsModes.getOrElse(position) { "AUTO" })
                BrainGraph.invalidate()
                refreshTtsHint()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        screen.findViewById<android.widget.EditText>(R.id.ttsCloudBaseUrlInput)?.apply {
            setText(JarvisSettings.ttsCloudBaseUrl(ctx))
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    JarvisSettings.setTtsCloudBaseUrl(ctx, text?.toString().orEmpty())
                    BrainGraph.invalidate()
                    refreshTtsHint()
                }
            }
        }
        screen.findViewById<android.widget.EditText>(R.id.ttsCloudApiKeyInput)?.apply {
            setText(
                JarvisSettings.ttsCloudApiKey(ctx),
            )
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    JarvisSettings.setTtsCloudApiKey(ctx, text?.toString().orEmpty())
                    BrainGraph.invalidate()
                    refreshTtsHint()
                }
            }
        }
    }

    private fun bindCompanionToggle(screen: View, id: Int, key: String, default: Boolean) {
        screen.findViewById<JarvisToggle>(id)?.apply {
            isChecked = JarvisSettings.prefs(this@DashboardActivity).getBoolean(key, default)
            onCheckedChanged = { checked ->
                JarvisSettings.prefs(this@DashboardActivity).edit().putBoolean(key, checked).apply()
            }
        }
    }

    private fun styleCompanionButtons(root: View) {
        if (root is android.widget.Button) {
            root.background = ContextCompat.getDrawable(this, R.drawable.bg_hud_panel)
            root.backgroundTintList = null
            root.setTextColor(ContextCompat.getColor(this, R.color.jarvis_cyan))
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) styleCompanionButtons(root.getChildAt(i))
        }
    }

    private var confirmDialog: android.app.Dialog? = null

    private fun showHudConfirm(toolId: String, message: String) {
        confirmDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
        }
        val timer = TextView(this).apply {
            text = "АВТООТМЕНА  10s"
            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_primary))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        box.addView(TextView(this).apply {
            text = "CONFIRM · $toolId"
            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        box.addView(TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_text))
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        box.addView(timer)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        fun finish(approved: Boolean) {
            dialog.dismiss()
            ActivityFeed.all().firstOrNull { it.pendingConfirm }?.let {
                ActivityFeed.markResolved(it.id, approved)
            }
            if (approved && VoiceController.isRunning) {
                VoiceController.notifyRequest(com.jarvis.core.common.model.UserRequest("да"))
            }
            if (currentScreenIndex == TAB_ACTIVITY) {
                screenContainer.findViewById<LinearLayout>(R.id.activityFeedContainer)?.let { renderActivityFeed(it) }
            }
        }
        row.addView(TextView(this).apply {
            text = "ПОДТВЕРДИТЬ"
            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_cyan))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad / 2, pad, pad / 2)
            background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_fui_active)
            setOnClickListener { finish(true) }
        })
        row.addView(TextView(this).apply {
            text = "ОТМЕНА"
            setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.jarvis_danger))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad / 2, pad, pad / 2)
            background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_hud_panel)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = pad / 2
            layoutParams = lp
            setOnClickListener { finish(false) }
        })
        box.addView(row)
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        var left = 10
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                timer.text = "АВТООТМЕНА  ${left}s"
                if (left <= 0) {
                    finish(false)
                    return
                }
                left -= 1
                confirmHandler.postDelayed(this, 1000)
            }
        }
        dialog.setOnDismissListener { confirmHandler.removeCallbacks(tick) }
        dialog.show()
        confirmDialog = dialog
        confirmHandler.post(tick)
    }

    private val confirmHandler = Handler(Looper.getMainLooper())

    private val memoryPipeline = MemoryCandidatePipeline()
    private val behaviorSimBuffer = mutableListOf<PhoneObservation>()

    private fun setupCompanionPanel(screen: View) {
        styleCompanionButtons(screen)
        bindCompanionToggle(screen, R.id.toggleCompanionProactive, "companion_proactive", true)
        bindCompanionToggle(screen, R.id.toggleCompanionNotify, "companion_bg_notify", true)
        bindCompanionToggle(screen, R.id.toggleQuietHours, "companion_quiet", false)
        bindCompanionToggle(screen, R.id.toggleOrbSnap, "companion_orb_snap", true)
        SystemStateObserver.ensureStarted(this)
        UsageStatsObserver.ensureStarted(this)
        val mem = CompanionMemory.get(this)
        val behavior = CompanionBehavior.get(this)
        val contextView = screen.findViewById<TextView>(R.id.companionContextText)
        val eventsView = screen.findViewById<TextView>(R.id.companionEventsText)
        val sourcesView = screen.findViewById<TextView>(R.id.companionSourcesText)
        val memoryView = screen.findViewById<TextView>(R.id.companionMemoryText)
        val durableView = screen.findViewById<TextView>(R.id.companionDurableMemoryText)
        val behaviorView = screen.findViewById<TextView>(R.id.companionBehaviorText)
        val proactiveView = screen.findViewById<TextView>(R.id.companionProactiveText)
        val proactiveButtons = screen.findViewById<View>(R.id.companionProactiveButtons)
        val diagnosticsView = screen.findViewById<TextView>(R.id.companionDiagnosticsText)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun sourceLine(ok: Boolean, label: String): String =
            "${if (ok) "●" else "○"} $label"

        fun topBehaviorKey(): String? =
            behavior.store().suggestions().firstOrNull()?.patternKey

        fun formatProactiveUserText(d: ProactiveDecision): String = buildString {
            when (d.type) {
                ProactiveDecisionType.SUGGESTION -> append("Предложение\n")
                ProactiveDecisionType.QUESTION -> append("Вопрос\n")
                ProactiveDecisionType.CONFIRMATION_REQUEST -> append("Подтверждение\n")
                ProactiveDecisionType.NO_OP -> return@buildString // не для user UI
            }
            append(d.reason.take(160).ifBlank { "Контекст совпадает с привычным поведением." })
            // patternKey только в debug — без technical reason codes в user UI
            if (BuildConfig.DEBUG) {
                d.behaviorPatternKey?.let { append("\n").append(it.take(64)) }
            }
        }

        fun formatProactiveDebug(d: ProactiveDecision): String = buildString {
            append("debug NO_OP · ").append(d.reasonCode.name)
            if (d.reason.isNotBlank()) append(" · ").append(d.reason.take(48))
        }

        fun render() {
            val a11y = PermissionsHelper.isAccessibilityServiceEnabled(
                this,
                JarvisAccessibilityService::class.java,
            )
            val nls = PermissionsHelper.isNotificationListenerEnabled(this)
            val usage = PermissionsHelper.isUsageAccessGranted(this)
            val assist = PermissionsHelper.isAssistantRoleHeld(this)
            val voiceOn = VoiceController.isRunning
            sourcesView?.text = listOf(
                sourceLine(assist, "Digital assistant"),
                sourceLine(a11y, "Accessibility"),
                sourceLine(nls, "Notifications"),
                sourceLine(usage, "Usage access"),
                sourceLine(voiceOn, "VoicePipeline (${VoiceController.lastEntry})"),
            ).joinToString("\n")

            val snap = PhoneContextBus.snapshot()
            val ctx = snap.context
            contextView?.text = buildString {
                append(ctx.describe())
                ctx.lastNotificationSummary?.let { append("\nlast: ").append(it) }
            }

            val recentAsc = ObservationDiagnosticsBuffer.recent(40)
            mem.ingest(snap, recentAsc)
            mem.expireNow()

            val decisions = memoryPipeline.evaluate(snap, recentAsc)
                .filter { it.disposition != MemoryDisposition.REJECT }
            memoryView?.text = if (decisions.isEmpty()) {
                "Нет новых кандидатов (≥3 FG / app switch)."
            } else {
                decisions.takeLast(4).asReversed().joinToString("\n") { d ->
                    val c = d.candidate
                    "${d.disposition.name} [${c.provenance.name}/${c.kind.name}] " +
                        "ev=${c.evidenceCount} conf=${String.format(Locale.US, "%.2f", c.confidence)}\n" +
                        c.statement.take(72)
                }
            }

            val active = mem.recall.active(12)
            val temps = mem.recall.temporary(8)
            val stale = mem.recall.staleOrExpired(6)
            durableView?.text = buildString {
                append("active=").append(active.size)
                append(" temp=").append(temps.size)
                append(" stale/exp=").append(stale.size)
                append('\n')
                if (active.isEmpty() && temps.isEmpty()) {
                    append("Пусто. «Запомнить тест» или накопить FG.")
                } else {
                    (active + temps).distinctBy { it.id }.take(6).forEach { e ->
                        append(e.status.name).append(' ')
                        append(e.disposition.name.take(4)).append(' ')
                        append('[').append(e.provenance.name.take(6)).append('/')
                        append(e.type.name.take(6)).append("] ")
                        append(String.format(Locale.US, "%.2f", e.confidence))
                        append(" ev=").append(e.evidenceCount)
                        append('\n')
                        append(e.content.take(70)).append('\n')
                    }
                    if (stale.isNotEmpty()) {
                        append("--- stale/expired ---\n")
                        stale.take(2).forEach { e ->
                            append(e.status.name).append(": ").append(e.content.take(50)).append('\n')
                        }
                    }
                }
            }

            val forLearning = (behaviorSimBuffer + recentAsc).distinctBy { it.id }
            behavior.evaluate(forLearning)
            val suggestions = behavior.store().suggestions()
            behaviorView?.text = if (suggestions.isEmpty()) {
                "Нет паттернов. Симуляция «наушники→Music ×5» или реальные повторы."
            } else {
                suggestions.take(3).joinToString("\n\n") { c ->
                    buildString {
                        append(c.kind.name).append('/').append(c.family.name)
                        append(" conf=").append(String.format(Locale.US, "%.0f%%", c.confidence * 100))
                        append(" ev=").append(c.evidenceCount)
                        append('\n').append(c.description)
                        append('\n').append("Why: ").append(c.why.take(90))
                    }
                }
            }

            // Stage 7.5: один pipeline — после BehaviorLearning
            CompanionProactive.tick(this, behavior, snap)
            val proactive = CompanionProactive.visibleDecision(this)
            if (proactive == null) {
                // Production: NO_OP → ничего. Debug: reason diagnostics.
                if (BuildConfig.DEBUG) {
                    val evaluated = CompanionProactive.lastEvaluatedDecision(this)
                    if (evaluated != null && evaluated.type == ProactiveDecisionType.NO_OP) {
                        proactiveView?.visibility = View.VISIBLE
                        proactiveView?.text = formatProactiveDebug(evaluated)
                    } else {
                        proactiveView?.visibility = View.GONE
                        proactiveView?.text = ""
                    }
                } else {
                    proactiveView?.visibility = View.GONE
                    proactiveView?.text = ""
                }
                proactiveButtons?.visibility = View.GONE
            } else {
                proactiveView?.visibility = View.VISIBLE
                proactiveView?.text = formatProactiveUserText(proactive)
                proactiveButtons?.visibility = View.VISIBLE
            }
            if (!JarvisSettings.prefs(this@DashboardActivity).getBoolean("companion_proactive", true)) {
                proactiveButtons?.visibility = View.GONE
                proactiveView?.visibility = View.GONE
            }

            val recent = recentAsc.asReversed().take(20)
            eventsView?.text = if (recent.isEmpty()) {
                "Пока нет событий."
            } else {
                recent.joinToString("\n") { o ->
                    val t = fmt.format(Date(o.timestampMillis))
                    val src = o.source.name.take(6)
                    val conf = String.format(Locale.US, "%.1f", o.confidence)
                    "$t · $src@$conf · ${o.type.name} · ${o.packageName ?: "—"}"
                }
            }

            runCatching {
                BrainGraph.build(this@DashboardActivity)
                val snapDiag = BrainGraph.diagnosticsSnapshot(this@DashboardActivity)
                diagnosticsView?.text = com.jarvis.core.diagnostics.DiagnosticsFormatter.render(snapDiag)
            }.onFailure {
                diagnosticsView?.text = "diagnostics unavailable: ${it.javaClass.simpleName}"
            }
        }

        fun applyBehaviorFeedback(fb: BehaviorFeedback) {
            val key = topBehaviorKey() ?: return
            behavior.feedback(key, fb, memory = mem)
            CompanionBehavior.persistFeedback(this, behavior)
            render()
        }

        fun applyProactiveFeedback(fb: BehaviorFeedback) {
            // ACCEPT = feedback only; не Intent / ToolCall / launch Music
            CompanionProactive.applyFeedback(this, behavior, fb, memory = mem)
            render()
        }

        screen.findViewById<Button>(R.id.btnCompanionRefresh)?.setOnClickListener { render() }
        screen.findViewById<Button>(R.id.btnCompanionNls)?.setOnClickListener {
            PermissionsHelper.openNotificationListenerSettings(this)
        }
        screen.findViewById<Button>(R.id.btnCompanionUsage)?.setOnClickListener {
            PermissionsHelper.openUsageAccessSettings(this)
        }
        screen.findViewById<Button>(R.id.btnCompanionAssistant)?.setOnClickListener {
            PermissionsHelper.openAssistantPickerSettings(this)
        }
        screen.findViewById<Button>(R.id.btnCompanionRemember)?.setOnClickListener {
            mem.rememberUserStatement(
                "Тест Stage 5.5: предпочитаю Jarvis как помощника",
                kind = MemoryKind.PREFERENCE,
                confidence = 1f,
            )
            render()
        }
        screen.findViewById<Button>(R.id.btnBehaviorAccept)?.setOnClickListener {
            applyBehaviorFeedback(BehaviorFeedback.ACCEPT)
        }
        screen.findViewById<Button>(R.id.btnBehaviorIgnore)?.setOnClickListener {
            applyBehaviorFeedback(BehaviorFeedback.IGNORE)
        }
        screen.findViewById<Button>(R.id.btnBehaviorDisable)?.setOnClickListener {
            applyBehaviorFeedback(BehaviorFeedback.DISABLE)
        }
        screen.findViewById<Button>(R.id.btnProactiveAccept)?.setOnClickListener {
            applyProactiveFeedback(BehaviorFeedback.ACCEPT)
        }
        screen.findViewById<Button>(R.id.btnProactiveIgnore)?.setOnClickListener {
            applyProactiveFeedback(BehaviorFeedback.IGNORE)
        }
        screen.findViewById<Button>(R.id.btnProactiveDisable)?.setOnClickListener {
            applyProactiveFeedback(BehaviorFeedback.DISABLE)
        }
        screen.findViewById<Button>(R.id.btnProactiveHeadphonesNow)?.setOnClickListener {
            // Контекст для Stage 7: наушники on, Music не на FG — без execution
            PhoneContextBus.publish(
                PhoneObservation(
                    timestampMillis = System.currentTimeMillis(),
                    source = ObservationSource.SYSTEM_BROADCAST,
                    type = ObservationType.HEADPHONES_CONNECTED,
                    summary = "Наушники подключены",
                    sensitivity = Sensitivity.PUBLIC,
                    confidence = 1f,
                ),
            )
            PhoneContextBus.publish(
                PhoneObservation(
                    timestampMillis = System.currentTimeMillis() + 1,
                    source = ObservationSource.ACCESSIBILITY,
                    type = ObservationType.APP_FOREGROUND,
                    packageName = "com.miui.home",
                    summary = "На переднем плане: launcher",
                    confidence = 0.9f,
                ),
            )
            // Один render в существующем pipeline (без force поверх уже сделанного tick)
            render()
        }
        screen.findViewById<Button>(R.id.btnBehaviorSimulate)?.setOnClickListener {
            behaviorSimBuffer.clear()
            val music = "com.google.android.apps.youtube.music"
            val cal = java.util.Calendar.getInstance()
            val hourNow = cal.get(java.util.Calendar.HOUR_OF_DAY)
            for (d in 0 until 5) {
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -(4 - d))
                cal.set(java.util.Calendar.HOUR_OF_DAY, hourNow)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val t0 = cal.timeInMillis
                val hp = PhoneObservation(
                    timestampMillis = t0,
                    source = ObservationSource.SYSTEM_BROADCAST,
                    type = ObservationType.HEADPHONES_CONNECTED,
                    summary = "Наушники подключены",
                    sensitivity = Sensitivity.PUBLIC,
                    confidence = 1f,
                )
                val fg = PhoneObservation(
                    timestampMillis = t0 + 120_000L,
                    source = ObservationSource.ACCESSIBILITY,
                    type = ObservationType.APP_FOREGROUND,
                    packageName = music,
                    summary = "На переднем плане: $music",
                    confidence = 0.9f,
                )
                behaviorSimBuffer += hp
                behaviorSimBuffer += fg
            }
            // Не спамим bus десятью событиями — learning берёт sim buffer напрямую
            render()
        }
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PhoneContextBus.observations.collect { render() }
            }
        }
    }

    private fun setupModelsPanel(screen: View) {
        val list = screen.findViewById<LinearLayout>(R.id.modelsList) ?: return
        val pathHint = screen.findViewById<TextView>(R.id.modelsPathHint)
        val mgr = ModelManager.get(this)
        pathHint?.text = mgr.modelsRoot().absolutePath
        mgr.refreshAll()

        fun render() {
            list.removeAllViews()
            val statuses = mgr.statuses.value
            ModelCatalog.ALL.forEach { entry ->
                val card = LayoutInflater.from(this).inflate(R.layout.item_model_card, list, false)
                val title = card.findViewById<TextView>(R.id.modelTitle)
                val subtitle = card.findViewById<TextView>(R.id.modelSubtitle)
                val statusTag = card.findViewById<TextView>(R.id.modelStatus)
                val progress = card.findViewById<android.widget.ProgressBar>(R.id.modelProgress)
                val message = card.findViewById<TextView>(R.id.modelMessage)
                val btnAction = card.findViewById<Button>(R.id.btnModelAction)
                val btnDelete = card.findViewById<Button>(R.id.btnModelDelete)
                title.text = entry.title
                val mb = entry.sizeBytes / 1_000_000
                subtitle.text = "${entry.subtitle} · ${mb} МБ"
                val st = statuses[entry.id] ?: ModelManager.Status(entry.id, ModelManager.State.MISSING)
                fun applyStatus(s: ModelManager.Status) {
                    statusTag.text = s.state.name
                    statusTag.setTextColor(
                        when (s.state) {
                            ModelManager.State.READY -> Color.parseColor("#62D9DF")
                            ModelManager.State.DOWNLOADING -> Color.parseColor("#E0AD55")
                            ModelManager.State.ERROR, ModelManager.State.CORRUPT -> Color.parseColor("#E07070")
                            else -> Color.parseColor("#E0AD55")
                        },
                    )
                    when (s.state) {
                        ModelManager.State.DOWNLOADING -> {
                            progress.visibility = View.VISIBLE
                            progress.progress = (s.progress * 100).toInt().coerceIn(0, 100)
                            message.visibility = View.VISIBLE
                            message.text = s.message.ifBlank {
                                "${s.bytesDownloaded / 1_000_000} / ${s.bytesTotal / 1_000_000} МБ"
                            }
                            btnAction.text = "Отмена"
                            btnAction.setOnClickListener { mgr.cancel(entry.id) }
                            btnDelete.visibility = View.GONE
                        }
                        ModelManager.State.READY -> {
                            progress.visibility = View.VISIBLE
                            progress.progress = 100
                            message.visibility = View.VISIBLE
                            message.text = "$mb МБ · готово"
                            val active = JarvisSettings.prefs(this@DashboardActivity)
                                .getString("active_chat_model", null) == entry.relativePath
                            btnAction.isEnabled = entry.kind == ModelCatalog.Kind.CHAT_LLM
                            btnAction.text = when {
                                active -> "АКТИВНА"
                                entry.kind == ModelCatalog.Kind.CHAT_LLM -> "ВЫБРАТЬ"
                                else -> "ПРОВЕРИТЬ"
                            }
                            btnAction.setOnClickListener {
                                if (entry.kind == ModelCatalog.Kind.CHAT_LLM) {
                                    JarvisSettings.prefs(this@DashboardActivity)
                                        .edit().putString("active_chat_model", entry.relativePath).apply()
                                    BrainGraph.invalidateChatLlm()
                                    render()
                                } else {
                                    mgr.refreshAll()
                                    message.text = "$mb МБ · файл на месте"
                                }
                            }
                            btnDelete.visibility = View.VISIBLE
                            btnDelete.setOnClickListener {
                                mgr.delete(entry.id)
                                render()
                            }
                        }
                        else -> {
                            progress.visibility = View.GONE
                            message.visibility = if (s.message.isNotBlank()) View.VISIBLE else View.GONE
                            message.text = s.message
                            btnAction.isEnabled = true
                            btnAction.text = "Скачать"
                            btnAction.setOnClickListener { mgr.download(entry.id) }
                            btnDelete.visibility = View.GONE
                        }
                    }
                }
                applyStatus(st)
                list.addView(card)
            }
            addCloudModelCard(list, "Облако · agnes-2.5-flash", "FAST", com.jarvis.brain.aiprovider.NaraRouterDefaults.MODEL_FAST)
            addCloudModelCard(list, "Облако · mistral-medium-3-5", "QUALITY", com.jarvis.brain.aiprovider.NaraRouterDefaults.MODEL_QUALITY)
        }
        render()
        lifecycleScope.launch {
            mgr.statuses.collect {
                if (screen.findViewById<View>(R.id.modelsPanel)?.visibility == View.VISIBLE) {
                    render()
                }
            }
        }
    }

    private fun addCloudModelCard(list: LinearLayout, titleText: String, profile: String, modelId: String) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_model_card, list, false)
        card.findViewById<TextView>(R.id.modelTitle).text = titleText
        card.findViewById<TextView>(R.id.modelSubtitle).text = "OpenAI-compatible · $modelId"
        val status = card.findViewById<TextView>(R.id.modelStatus)
        val active = JarvisSettings.chatProfile(this).equals(profile, ignoreCase = true)
        status.text = if (active) "ACTIVE" else "CLOUD"
        val progress = card.findViewById<android.widget.ProgressBar>(R.id.modelProgress)
        progress.visibility = View.VISIBLE
        progress.progress = if (active) 100 else 8
        val message = card.findViewById<TextView>(R.id.modelMessage)
        message.visibility = View.VISIBLE
        message.text = "пинг не измерен"
        card.findViewById<android.widget.Button>(R.id.btnModelDelete).visibility = View.GONE
        val action = card.findViewById<android.widget.Button>(R.id.btnModelAction)
        action.text = "PING"
        action.setOnClickListener {
            if (!active) {
                JarvisSettings.setChatProfile(this, profile)
                status.text = "ACTIVE"
            }
            Thread {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val baseUrl = JarvisSettings.cloudBaseUrl(this).trim().trimEnd('/')
                val ms = if (baseUrl.isBlank()) {
                    -1L
                } else runCatching {
                    val conn = java.net.URL("$baseUrl/models")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer ${JarvisSettings.cloudApiKey(this)}")
                    conn.responseCode
                    android.os.SystemClock.elapsedRealtime() - t0
                }.getOrDefault(-1L)
                runOnUiThread {
                    message.text = if (ms >= 0) "Cloud · ${ms} мс" else "Cloud · нет ответа"
                    if (ms >= 0) status.text = "${ms}ms"
                }
            }.start()
        }
        list.addView(card)
    }
}
