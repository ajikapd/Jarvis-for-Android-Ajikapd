package com.jarvis.app.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.app.ActivityFeed
import com.jarvis.app.ConversationStore
import com.jarvis.app.CrashReports
import com.jarvis.app.JarvisSettings
import com.jarvis.app.NativeGuard
import com.jarvis.app.OnboardingActivity
import com.jarvis.app.OverlayOrbService
import com.jarvis.app.UiMode
import com.jarvis.app.UsageGuideActivity
import com.jarvis.app.VoiceController
import com.jarvis.app.ui.JarvisActivity
import com.jarvis.app.ui.Skins
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import com.jarvis.core.startup.UiStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Джарвис Pro: экраны Pro (assets/pro, WebView) поверх настоящего Джарвиса.
 * Голос, слово «Джарвис», распознавание, нейросеть, инструменты и агент — те же, что в обычном приложении;
 * экраны получают настоящее состояние (ожидаю / слушаю / думаю / говорю), переписку, ленту действий и подтверждения.
 * Настройки, модели, память, навыки и рутины открываются настоящими разделами (в цвете темы Pro).
 */
class ProActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var state = AssistantState.OFFLINE
    private var askingYesNo = false
    private var pageReady = false
    /** Высота настоящей строки уведомлений, dp (= CSS-пиксели в WebView). */
    @Volatile private var statusTop = 0f

    private val isOn: Boolean get() = state != AssistantState.OFFLINE && state != AssistantState.ERROR
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (needsSetup()) js("window.proPerm&&proPerm()") else askNotificationsThenStart()
        } else js("window.proToast&&proToast('Без микрофона Джарвис не услышит, сэр')")
    }
    private val proData by lazy { ProData(applicationContext) }
    private val panelData by lazy { ProPanelData(applicationContext) }
    private val ownerRecordLock = Any()
    private var ownerRecording = false
    private val ownerWords = ArrayList<FloatArray>()
    private val ownerPhrases = ArrayList<List<FloatArray>>()
    private val dataPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        js("window.proPerm&&proPerm()")
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (needsSetup()) js("window.proPerm&&proPerm()") else VoiceController.start(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Без действующего ключа Pro — экран «Активация» вместо Джарвиса.
        if (!ProLicense.isActive(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        // В Pro свой интерфейс и своя экскурсия: выбор интерфейса и старый гид не нужны.
        if (UiMode.style(this) == null) UiMode.set(this, UiStyle.MODERN)
        UsageGuideActivity.markDone(this)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        web = WebView(this).apply {
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageReady = false
            }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                val u = r.url
                if (HOST != u.host) return WebResourceResponse("text/plain", "utf-8", 403, "Offline", null, null)
                var path = u.path
                if (path.isNullOrEmpty() || path == "/") path = "/index.html"
                return try {
                    val input = assets.open("pro$path", AssetManager.ACCESS_STREAMING)
                    val mime = mime(path)
                    WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript")) "utf-8" else null, input).apply {
                        responseHeaders = mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "*")
                    }
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                }
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = HOST != r.url.host

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushState()
                // подтверждение, открытое до перехода на другую страницу, показываем снова
                if (askingYesNo) confirmMsg?.let { js("window.proConfirm&&proConfirm(${JSONObject.quote(it)})") }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // Система выгрузила процесс экранов (мало памяти): без этого Android закрыл бы всё приложение вместе с голосом.
                pageReady = false
                root.removeView(web)
                val now = android.os.SystemClock.elapsedRealtime()
                renderCrashes.removeAll { now - it > 30_000L }
                renderCrashes.add(now)
                if (renderCrashes.size > 2) {
                    // Падает снова и снова — не крутимся по кругу, голос продолжает работать.
                    root.addView(android.widget.TextView(this@ProActivity).apply {
                        text = "Экраны Джарвиса не смогли загрузиться (мало памяти).\nГолос работает. Закройте и снова откройте приложение."
                        setTextColor(0xFFEEF1F6.toInt()); textSize = 16f; setPadding(48, 48, 48, 48)
                        gravity = android.view.Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                } else if (!isFinishing) root.post { recreate() }
                return true
            }
        }
        // без серого окна с кнопкой «play», пока видео грузится
        web.webChromeClient = object : WebChromeClient() {
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        web.addJavascriptInterface(Bridge(), "JarvisNative")
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        fullscreen()
        if (savedInstanceState == null && CrashReports.takePrompt(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Отчёт об ошибке")
                .setMessage("Джарвис недавно закрылся с ошибкой. Отправить отчёт разработчику? В нём модель телефона и техническая часть, без ваших команд и переписки.")
                .setPositiveButton("Отправить") { _, _ -> startActivity(CrashReports.shareIntent(this)) }
                .setNegativeButton("Не надо", null)
                .show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.appBack?appBack():false") { v -> if (v != "true") moveTaskToBack(true) }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { JarvisEvents.assistantState.collect(::onState) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { JarvisEvents.events.collect(::onEvent) }
        }
        web.loadUrl("https://$HOST/index.html")
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Виджет, плитка, системный помощник — как слово «Джарвис». */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_ASSIST) wakeWhenReady()
        else if (intent.action == JarvisActivity.ACTION_POWER_ON) powerOn()
    }

    private var wakeJob: Job? = null

    private fun wakeWhenReady() {
        if (wakeJob?.isActive == true) return
        wakeJob = lifecycleScope.launch {
            if (!VoiceController.isRunning) powerOn()
            repeat(STARTUP_POLLS * 2) {
                val st = JarvisEvents.assistantState.value
                if (VoiceController.isRunning && (st == AssistantState.READY || st == AssistantState.IDLE)) {
                    delay(WAKE_SETTLE_MS)
                    VoiceController.manualWake()
                    return@launch
                }
                delay(STARTUP_POLL_MS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (needsSetup()) return
        // Был включён до закрытия или обновления — поднимаем без лишнего нажатия (как в обычном Джарвисе).
        if (JarvisSettings.voiceWanted(this) && !VoiceController.isRunning && !NativeGuard.isOff(this, NativeGuard.VOICE_AUTOSTART)) {
            NativeGuard.begin(this, NativeGuard.VOICE_AUTOSTART)
            powerOn()
            root.postDelayed({ NativeGuard.end(this, NativeGuard.VOICE_AUTOSTART) }, AUTOSTART_SAFE_MS)
        }
    }

    override fun onResume() {
        if (pageReady) js("window.proPerm&&proPerm()")
        super.onResume()
        if (!ProLicense.isActive(this)) { // срок кончился, пока приложение было открыто
            VoiceController.stop(this)
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        VoiceController.hideOverlay()
        OverlayOrbService.hide(this)
        web.onResume()
        js("window.appPause&&appPause(false)")
        pushState()
        js("window.proEvent&&proEvent({t:'history'})")
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && VoiceController.liveTalk && Settings.canDrawOverlays(this)) VoiceController.showOverlay()
        web.onPause()
        js("window.appPause&&appPause(true)")
    }

    override fun onStop() {
        super.onStop()
        runCatching { applyIcon() }
    }

    override fun onDestroy() {
        if (::web.isInitialized) { // окно могло закрыться сразу (нет ключа Pro)
            root.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }

    // ---------- голос ----------
    /** Первый запуск и разрешения остаются внутри визуальной оболочки Pro. */
    private fun needsSetup(): Boolean = prefs.getString("firstDone", "") == "1" && OnboardingActivity.needsOnboarding(this)

    private fun startSetup() {
        web.loadUrl("https://$HOST/panel.html#setup")
    }

    fun powerOn() {
        if (needsSetup()) { startSetup(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        askNotificationsThenStart()
    }

    private fun askNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else VoiceController.start(this)
    }

    fun powerOff() {
        VoiceController.liveTalk = false
        VoiceController.stop(this)
    }

    /** Микрофон работает как слово «Джарвис»; во время ответа — прерывает. */
    private fun onMic() {
        when {
            !isOn || state == AssistantState.STARTING -> wakeWhenReady()
            state == AssistantState.LISTENING || state == AssistantState.THINKING || state == AssistantState.EXECUTING -> VoiceController.cancelTurn(this)
            else -> VoiceController.manualWake()
        }
    }

    /** Текстовый запрос — тем же путём, что голос. */
    private fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!VoiceController.isRunning) powerOn()
        lifecycleScope.launch {
            repeat(STARTUP_POLLS) {
                if (VoiceController.isRunning) {
                    ConversationStore.pushUser(clean)
                    js("window.proEvent&&proEvent(${JSONObject().put("t", "history").toString()})")
                    VoiceController.notifyRequest(UserRequest(clean))
                    return@launch
                }
                delay(STARTUP_POLL_MS)
            }
            ConversationStore.pushAi("Сервис голоса ещё запускается, сэр. Повторите через минуту.")
            js("window.proEvent&&proEvent({t:'history'})")
        }
    }

    private fun answer(yes: Boolean) {
        askingYesNo = false
        confirmMsg = null
        ActivityFeed.all().firstOrNull { it.pendingConfirm }?.let { ActivityFeed.markResolved(it.id, yes) }
        if (VoiceController.isRunning) VoiceController.notifyRequest(UserRequest(if (yes) "да" else "нет"))
    }

    private fun onState(s: AssistantState) {
        state = s
        pushState()
    }

    private fun pushState() {
        if (pageReady) js("window.proState&&proState('${state.name}')")
    }

    private fun event(type: String, text: String = "") {
        js("window.proEvent&&proEvent(${JSONObject().put("t", type).put("text", text)})")
    }

    private fun onEvent(e: AssistantEvent) {
        when (e) {
            is AssistantEvent.TranscriptUpdated -> event("partial", e.text)
            is AssistantEvent.RequestReceived -> {
                ConversationStore.pushUser(e.text)
                ActivityFeed.push("Запрос", e.text, ActivityFeed.Item.Kind.VOICE)
                if (askingYesNo) { askingYesNo = false; js("window.proConfirm&&proConfirm(null)") }
                event("request", e.text)
            }
            is AssistantEvent.ResponseGenerated -> {
                ConversationStore.pushAi(e.text)
                ActivityFeed.push("Ответ", e.text.take(FEED_TEXT), ActivityFeed.Item.Kind.VOICE)
                event("response", e.text)
                if (e.text.contains(YES_OR_NO_SIR) || (e.text.contains(YES_OR_NO) && ActivityFeed.all().any { it.pendingConfirm })) confirm(confirmText(e.text))
                else if (askingYesNo) { askingYesNo = false; js("window.proConfirm&&proConfirm(null)") }
            }
            is AssistantEvent.IntentResolved -> event("intent")
            is AssistantEvent.ToolExecuted -> {
                val pending = e.result.status == ToolResultStatus.NEEDS_CONFIRMATION
                val failed = e.result.status != ToolResultStatus.SUCCESS && !pending
                ActivityFeed.push(
                    e.result.toolId, e.result.message.orEmpty(),
                    if (pending) ActivityFeed.Item.Kind.CONFIRM else ActivityFeed.Item.Kind.TOOL,
                    pendingConfirm = pending,
                    failed = failed,
                )
                event("tool", e.result.message.orEmpty())
                if (pending) confirm(e.result.message ?: "Подтвердите действие, сэр.")
            }
            AssistantEvent.WakeDetected -> {
                ActivityFeed.push("Активация", "Услышал обращение", ActivityFeed.Item.Kind.SYSTEM)
                event("wake")
            }
            else -> return
        }
    }

    /** Текст вопроса без «Да или нет, сэр?» (фраза бывает и в начале, и в конце). */
    private fun confirmText(text: String): String {
        val t = text.replace("Да или нет, сэр?", " ").replace(YES_OR_NO, " ").replace(Regex("\\s+"), " ")
            .trim().trim(',', '.', '?', '!', ' ', '—')
        return t.ifEmpty { "Подтвердите действие, сэр." }
    }

    private var confirmMsg: String? = null

    private fun confirm(message: String) {
        askingYesNo = true
        confirmMsg = message
        js("window.proConfirm&&proConfirm(${JSONObject.quote(message)})")
    }

    private fun js(code: String) {
        if (!::web.isInitialized) return
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(code, null) }
    }

    // ---------- настоящие разделы ----------
    private fun openNative(target: String) {
        when {
            target == "overlay" -> {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            target == "setup" || target == "live" || target.startsWith("section:") || target.startsWith("screen:") -> {
                web.loadUrl("https://$HOST/panel.html#${Uri.encode(target)}")
                return
            }
            else -> return
        }
    }

    private suspend fun recordOwner(type: String): JSONObject {
        if (type != "word" && type != "phrase") return JSONObject().put("err", "Неизвестный тип записи")
        if (!com.jarvis.app.VoiceIdentity.modelReady(this)) return JSONObject().put("err", "Сначала скачайте модель узнавания")
        synchronized(ownerRecordLock) {
            if (ownerRecording) return JSONObject().put("err", "Запись уже идёт")
            ownerRecording = true
        }
        return try {
            val sample = CompletableDeferred<FloatArray?>()
            val duration = if (type == "word") 2500 else com.jarvis.app.VoiceIdentity.PHRASE_MS
            runOnUiThread {
                if (!VoiceController.recordVoiceSample(duration) { sample.complete(it) }) sample.complete(null)
            }
            val pcm = withTimeoutOrNull(duration + 4000L) { sample.await() }
                ?: return JSONObject().put("err", "Включите ассистента и повторите запись")
            if (type == "word") {
                val embedding = com.jarvis.app.VoiceIdentity.embedSample(this, pcm)
                    ?: return JSONObject().put("err", "Речь не расслышана. Повторите громче")
                ownerWords.add(embedding)
                val needed = com.jarvis.wakeword.VoicePrint.SAMPLES_NEEDED
                if (ownerWords.size >= needed) {
                    com.jarvis.app.VoiceIdentity.save(this, ownerWords.toList())
                    ownerWords.clear()
                    JSONObject().put("ok", true).put("msg", "Слово записано. Теперь прочитайте фразы")
                } else JSONObject().put("ok", true).put("msg", "Запись ${ownerWords.size} из $needed")
            } else {
                val pieces = com.jarvis.app.VoiceIdentity.embedPhrase(this, pcm)
                if (pieces.isEmpty()) return JSONObject().put("err", "Фраза не расслышана. Повторите")
                ownerPhrases.add(pieces)
                val needed = com.jarvis.app.VoiceIdentity.PHRASES.size
                if (ownerPhrases.size >= needed) {
                    com.jarvis.app.VoiceIdentity.savePhrases(this, ownerPhrases.toList())
                    ownerPhrases.clear()
                    JSONObject().put("ok", true).put("msg", "Образец владельца готов")
                } else JSONObject().put("ok", true).put("msg", "Фраза ${ownerPhrases.size} из $needed")
            }
        } finally { synchronized(ownerRecordLock) { ownerRecording = false } }
    }

    // ---------- значок в цвет темы ----------
    private fun applyIcon() {
        val want = prefs.getString("iconWant", "gold") ?: "gold"
        val pm = packageManager
        val target = ICONS.indexOfFirst { it.first == want }.coerceAtLeast(0)
        val on = BooleanArray(ICONS.size)
        var same = true
        ICONS.forEachIndexed { i, (_, alias) ->
            val st = pm.getComponentEnabledSetting(ComponentName(this, "com.jarvis.app.pro.$alias"))
            on[i] = st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && i == 0)
            if (on[i] != (i == target)) same = false
        }
        if (same) return
        pm.setComponentEnabledSetting(ComponentName(this, "com.jarvis.app.pro.${ICONS[target].second}"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        ICONS.forEachIndexed { i, (_, alias) ->
            if (i != target && on[i]) pm.setComponentEnabledSetting(ComponentName(this, "com.jarvis.app.pro.$alias"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    private fun fullscreen() {
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.navigationBarColor = 0
            window.isNavigationBarContrastEnforced = false
            window.statusBarColor = 0
            window.isStatusBarContrastEnforced = false
            // сверху — настоящая строка уведомлений телефона (прозрачная, светлые значки)
            window.insetsController?.apply {
                show(WindowInsets.Type.statusBars())
                setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
            root.setOnApplyWindowInsetsListener { v, ins ->
                statusTop = ins.getInsets(WindowInsets.Type.statusBars()).top / resources.displayMetrics.density
                val nav = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
                v.setPadding(0, 0, 0, maxOf(ime, if (nav > 30 * resources.displayMetrics.density) nav else 0))
                ins
            }
        }
    }

    /** Мост для экранов Pro. Всё, что меняет телефон, идёт через подтверждения самого Джарвиса. */
    inner class Bridge {
        @JavascriptInterface fun get(k: String): String = prefs.getString(k, "") ?: ""
        @JavascriptInterface fun set(k: String, v: String) {
            prefs.edit().putString(k, v).apply()
            if (k == "theme" || k == "curTheme") runOnUiThread { Skins.set(this@ProActivity, "pro_" + (v.ifEmpty { "gold" })) }
            if (k == "firstDone" && v == "1") runOnUiThread { if (OnboardingActivity.needsOnboarding(this@ProActivity)) startSetup() }
        }
        @JavascriptInterface fun version(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("") ?: ""
        @JavascriptInterface fun real(): Boolean = true
        @JavascriptInterface fun insetTop(): Float {
            if (statusTop > 0f) return statusTop
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) / resources.displayMetrics.density else 0f
        }
        @JavascriptInterface fun state(): String = state.name
        @JavascriptInterface fun power(on: Boolean) = runOnUiThread {
            when {
                !on -> powerOff()
                state == AssistantState.ERROR -> { powerOff(); root.postDelayed({ powerOn() }, 800) }
                else -> powerOn()
            }
        }
        @JavascriptInterface fun dismissConfirm() = runOnUiThread { askingYesNo = false; confirmMsg = null }
        @JavascriptInterface fun mic() = runOnUiThread { onMic() }
        /** Данные телефона для экранов Pro: ответ приходит в window.proData(id, json). */
        @JavascriptInterface fun q(id: String, kind: String, args: String) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val parsed = runCatching { JSONObject(args) }.getOrDefault(JSONObject())
                val res = runCatching {
                    when {
                        kind == "panelRecord" -> recordOwner(parsed.optString("type"))
                        kind.startsWith("panel") -> panelData.handle(kind, parsed)
                        else -> proData.handle(kind, parsed)
                    }
                }.getOrElse { JSONObject().put("err", it.message ?: "Действие не выполнено") }
                if (kind == "panelVoice") {
                    res.put("wordSamples", ownerWords.size)
                    res.put("phraseSamples", ownerPhrases.size)
                    res.put("wordNeeded", com.jarvis.wakeword.VoicePrint.SAMPLES_NEEDED)
                    res.put("phraseNeeded", com.jarvis.app.VoiceIdentity.PHRASES.size)
                    res.put("nextPhrase", com.jarvis.app.VoiceIdentity.PHRASES.getOrNull(ownerPhrases.size) ?: "")
                }
                if (kind == "panelVoiceForget" && res.optBoolean("ok")) {
                    ownerWords.clear()
                    ownerPhrases.clear()
                }
                if (kind == "voiceSet" && res.optBoolean("ok") && VoiceController.isRunning)
                    runOnUiThread { VoiceController.reloadVoice { VoiceController.speak("Добрый день, сэр. Так теперь звучит мой голос.") } }
                if (res.optBoolean("restart")) runOnUiThread {
                    if (VoiceController.isRunning) {
                        VoiceController.stop(this@ProActivity)
                        root.postDelayed({ VoiceController.start(this@ProActivity) }, 350)
                    }
                }
                if (kind == "panelSet" && parsed.optString("key") == "live" && parsed.optBoolean("value"))
                    runOnUiThread { if (!VoiceController.isRunning) powerOn() }
                js("window.proData&&proData(" + JSONObject.quote(id) + "," + res + ")")
            }
        }
        /** Попросить разрешение: calendar, contacts, calllog, sms, bluetooth, location, notifications, dnd, write. */
        @JavascriptInterface fun perm(name: String) = runOnUiThread {
            when (name) {
                "setup" -> audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                "postNotifications" -> if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                "battery" -> com.jarvis.onboarding.PermissionsHelper.openBatteryOptimizationSettings(this@ProActivity)
                "autostart" -> {
                    getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(OnboardingActivity.PREF_AUTOSTART_VISITED, true).apply()
                    com.jarvis.onboarding.PermissionsHelper.openAutoStartSettings(this@ProActivity)
                }
                "notifications" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "dnd" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "write" -> startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                "usage" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                "accessibility" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "assistant" -> com.jarvis.onboarding.PermissionsHelper.openAssistantPickerSettings(this@ProActivity)
                else -> {
                    val p = ProData.PERMS[name]
                    if (p != null) dataPermission.launch(if (name == "contacts") arrayOf(p, Manifest.permission.READ_CALL_LOG) else arrayOf(p))
                }
            }
        }
        @JavascriptInterface fun finishSetup() = runOnUiThread {
            val microphone = ContextCompat.checkSelfPermission(this@ProActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this@ProActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!microphone || !notifications) {
                js("window.proToast&&proToast('Сначала разрешите микрофон и уведомления')")
            } else {
                getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(OnboardingActivity.PREF_ONBOARDING_DONE, true)
                    .apply()
                powerOn()
                js("window.proSetupDone&&proSetupDone()")
            }
        }
        @JavascriptInterface fun send(text: String) = runOnUiThread { this@ProActivity.send(text) }
        @JavascriptInterface fun answer(yes: Boolean) = runOnUiThread { this@ProActivity.answer(yes) }
        @JavascriptInterface fun open(target: String) = runOnUiThread { openNative(target) }
        @JavascriptInterface fun shareReport() = runOnUiThread { startActivity(CrashReports.shareIntent(this@ProActivity)) }
        @JavascriptInterface fun previewVoice() = runOnUiThread {
            if (VoiceController.isRunning) VoiceController.speak("Так меня слышно, сэр.")
        }
        @JavascriptInterface fun history(): String {
            val a = JSONArray()
            ConversationStore.all().takeLast(60).forEach { b ->
                if (b.role == ConversationStore.Bubble.Role.SYSTEM) return@forEach
                a.put(JSONObject().put("me", b.role == ConversationStore.Bubble.Role.USER).put("text", b.text)
                    .put("at", ConversationStore.formatTime(b.atMs)).put("badge", b.badge ?: ""))
            }
            return a.toString()
        }
        @JavascriptInterface fun info(): String {
            val dayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }.timeInMillis
            return JSONObject()
                .put("router", JarvisSettings.routerMode(this@ProActivity))
                .put("msgs", ConversationStore.all().count { it.role != ConversationStore.Bubble.Role.SYSTEM })
                .put("tools", ActivityFeed.all().count { it.kind == ActivityFeed.Item.Kind.TOOL && !it.failed && it.atMs >= dayStart })
                .toString()
        }
        @JavascriptInterface fun feed(): String {
            val a = JSONArray()
            ActivityFeed.all().filter { it.kind != ActivityFeed.Item.Kind.SYSTEM }.take(12).forEach {
                a.put(JSONObject().put("title", it.title).put("detail", it.detail).put("at", ActivityFeed.formatTime(it.atMs))
                    .put("kind", it.kind.name).put("pending", it.pendingConfirm).put("failed", it.failed))
            }
            return a.toString()
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val PREFS = "jarvis_pro"
        const val BG = 0xFF020304.toInt()
        val ICONS = listOf("gold" to "IconGold", "az" to "IconAz", "am" to "IconAm", "ru" to "IconRu", "em" to "IconEm")
        private const val STARTUP_POLLS = 40
        private const val WAKE_SETTLE_MS = 400L
        private const val STARTUP_POLL_MS = 500L
        private const val AUTOSTART_SAFE_MS = 20_000L
        private const val FEED_TEXT = 120
        private const val YES_OR_NO = "Да или нет"
        private const val YES_OR_NO_SIR = "Да или нет, сэр"
        private val renderCrashes = ArrayList<Long>()

        fun mime(p: String): String {
            val s = p.lowercase()
            return when {
                s.endsWith(".html") -> "text/html"
                s.endsWith(".js") -> "application/javascript"
                s.endsWith(".css") -> "text/css"
                s.endsWith(".mp4") -> "video/mp4"
                s.endsWith(".png") -> "image/png"
                s.endsWith(".jpg") || s.endsWith(".jpeg") -> "image/jpeg"
                s.endsWith(".ttf") -> "font/ttf"
                s.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }
        }
    }
}
