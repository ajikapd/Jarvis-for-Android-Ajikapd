package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jarvis.app.ui.Fonts
import com.jarvis.app.ui.ReactorView
import com.jarvis.app.ui.Skin
import com.jarvis.app.ui.Skins
import com.jarvis.app.ui.alpha
import com.jarvis.app.ui.column
import com.jarvis.app.ui.dp
import com.jarvis.app.ui.glassBg
import com.jarvis.app.ui.label
import com.jarvis.app.ui.pad
import com.jarvis.app.ui.rowLayout
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Орб разговора Live: реактор с главного экрана и панель. Появляется только при сворачивании Live.
 */
class OverlayOrbService : Service() {

    private var window: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var core: ReactorView? = null
    private var said: TextView? = null
    private var lastState = AssistantState.LISTENING
    private var pod: LinearLayout? = null
    private var status: TextView? = null
    private var expanded = false
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                remove()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LIVE -> openLive()
        }
        if (!VoiceController.liveTalk || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val note = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, note)
        }
        show()
        return START_STICKY
    }

    private fun show() {
        if (root != null) return
        window = getSystemService(WindowManager::class.java)
        val skin = Skins.current(this)
        val size = dp(ORB_DP)
        val reactor = ReactorView(this, skin)
        core = reactor
        // Тот же реактор, что на главном экране, на тёмной подложке, чтобы читался поверх любого приложения.
        val orb = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(alpha(skin.bg, .92f))
                setStroke(dp(1), alpha(skin.accent, .8f))
            }
            contentDescription = "Орб Live"
            addView(reactor, FrameLayout.LayoutParams(-1, -1))
        }
        val podView = buildPod(skin)
        pod = podView
        podView.visibility = View.GONE
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orb, LinearLayout.LayoutParams(size, size))
            addView(podView, LinearLayout.LayoutParams(dp(POD_DP), -2).apply { marginStart = dp(8) })
        }
        root = box
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
            // Сохранённое место могло остаться от другого размера: орб всегда виден целиком.
            val maxX = resources.displayMetrics.widthPixels - size - dp(12)
            val maxY = resources.displayMetrics.heightPixels - size - dp(12)
            x = prefs.getInt("overlay_x", maxX).coerceIn(dp(12), maxX)
            y = prefs.getInt("overlay_y", resources.displayMetrics.heightPixels / 2).coerceIn(dp(12), maxY)
        }
        params = lp
        orb.setOnTouchListener(dragListener(box, lp))
        runCatching { window?.addView(box, lp) }
        scope.launch {
            JarvisEvents.assistantState.collect { state ->
                lastState = state
                paint()
            }
        }
        scope.launch {
            JarvisEvents.events.collect { event ->
                when (event) {
                    is AssistantEvent.RequestReceived -> said?.text = "«${event.text}»"
                    is AssistantEvent.ResponseGenerated -> said?.text = event.text
                    else -> Unit
                }
            }
        }
    }

    private fun paint() {
        val muted = VoiceController.liveMuted
        core?.mode = when {
            muted -> ReactorView.Mode.OFF
            lastState == AssistantState.THINKING -> ReactorView.Mode.THINK
            lastState == AssistantState.EXECUTING -> ReactorView.Mode.ACT
            lastState == AssistantState.SPEAKING -> ReactorView.Mode.SPEAK
            else -> ReactorView.Mode.LISTEN
        }
        status?.text = when {
            muted -> "Микрофон выключен"
            lastState == AssistantState.THINKING || lastState == AssistantState.EXECUTING -> "Думаю"
            lastState == AssistantState.SPEAKING -> "Говорю"
            else -> "Слушаю"
        }
    }

    private fun buildPod(skin: Skin): LinearLayout {
        val box = column(8).pad(12).apply { background = glassBg(skin, 18, alpha(skin.bg, .96f), skin.line) }
        val st = label("", 12f, skin.good)
        status = st
        box.addView(st)
        val text = label("Разговор продолжается, сэр.", 14f, skin.text, Fonts.body(this, 600), lines = 3)
        said = text
        box.addView(text)
        val row = rowLayout(6)
        val micText = chip(skin, "Микрофон", skin.text) {}
        micText.setOnClickListener {
            VoiceController.liveMuted = !VoiceController.liveMuted
            micText.text = if (VoiceController.liveMuted) "Включить" else "Микрофон"
            paint()
        }
        row.addView(micText, LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(chip(skin, "В Live", skin.text) { openLive() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(chip(skin, "Завершить", skin.danger) { endTalk() }, LinearLayout.LayoutParams(0, dp(38), 1.2f))
        box.addView(row)
        return box
    }

    private fun chip(skin: Skin, text: String, color: Int, click: () -> Unit): TextView =
        label(text, 11.5f, color, gravity = Gravity.CENTER, lines = 1).apply {
            gravity = Gravity.CENTER
            background = glassBg(skin, 12, alpha(skin.text, .05f), if (color == skin.danger) skin.danger else skin.line)
            setOnClickListener {
                HudFeedback.tick(this)
                click()
            }
        }

    /** Разговор завершён: орбу больше нечего показывать. */
    private fun endTalk() {
        VoiceController.liveTalk = false
        VoiceController.liveMuted = false
        VoiceController.stopSpeaking()
        remove()
        stopSelf()
    }

    private fun dragListener(view: View, lp: WindowManager.LayoutParams) = View.OnTouchListener { _, event ->
        val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.setTag(R.id.homeOrb, floatArrayOf(event.rawX, event.rawY, lp.x.toFloat(), lp.y.toFloat(), 0f))
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val tag = view.getTag(R.id.homeOrb) as? FloatArray ?: return@OnTouchListener true
                val dx = event.rawX - tag[0]
                val dy = event.rawY - tag[1]
                if (dx * dx + dy * dy > 64f) tag[4] = 1f
                if (tag[4] == 1f) {
                    lp.x = (tag[2] + dx).toInt()
                    lp.y = (tag[3] + dy).toInt()
                    runCatching { window?.updateViewLayout(view, lp) }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tag = view.getTag(R.id.homeOrb) as? FloatArray
                if (tag == null || tag[4] == 0f) {
                    togglePod()
                } else {
                    val screenW = resources.displayMetrics.widthPixels
                    val margin = (12 * resources.displayMetrics.density).toInt()
                    val width = view.width.coerceAtLeast(1)
                    val snap = getSharedPreferences("jarvis_prefs", MODE_PRIVATE).getBoolean("companion_orb_snap", true)
                    lp.x = if (!snap) lp.x.coerceIn(0, screenW - width)
                    else if (lp.x + width / 2 < screenW / 2) margin
                    else screenW - width - margin
                    lp.y = lp.y.coerceIn(0, resources.displayMetrics.heightPixels - view.height)
                    runCatching { window?.updateViewLayout(view, lp) }
                    prefs.edit().putInt("overlay_x", lp.x).putInt("overlay_y", lp.y).apply()
                }
                true
            }
            else -> false
        }
    }

    private fun togglePod() {
        expanded = !expanded
        pod?.visibility = if (expanded) View.VISIBLE else View.GONE
        val lp = params ?: return
        val view = root ?: return
        view.post {
            val screenW = resources.displayMetrics.widthPixels
            val margin = (12 * resources.displayMetrics.density).toInt()
            // Панель раскрывается в сторону экрана: орб не должен уезжать за край.
            if (lp.x + view.width > screenW - margin || !expanded) {
                if (lp.x > screenW / 3) lp.x = screenW - view.width - margin
            }
            runCatching { window?.updateViewLayout(view, lp) }
        }
    }

    private fun openLive() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch?.putExtra("open_live", true)
        if (launch != null) startActivity(launch)
    }

    private fun remove() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        root?.let { runCatching { window?.removeView(it) } }
        root = null
        pod = null
        core = null
    }

    private fun notification(): Notification {
        val channelId = "jarvis_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Орб Live", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("JARVIS")
            .setContentText("Идёт разговор Live")
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remove()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.jarvis.assistant.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.jarvis.assistant.OVERLAY_HIDE"
        const val ACTION_LIVE = "com.jarvis.assistant.OVERLAY_LIVE"
        private const val NOTIFICATION_ID = 42
        private const val ORB_DP = 76
        private const val POD_DP = 236

        /** Орб существует только во время разговора Live. */
        fun show(context: Context) {
            if (!VoiceController.liveTalk) return
            val intent = Intent(context, OverlayOrbService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            context.startService(Intent(context, OverlayOrbService::class.java).setAction(ACTION_HIDE))
        }
    }
}
