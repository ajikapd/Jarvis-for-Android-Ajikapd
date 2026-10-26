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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating 56dp core plus a compact HUD pod. Drag snaps to the nearer screen edge.
 */
class OverlayOrbService : Service() {

    private var window: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var core: OverlayCoreView? = null
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
            ACTION_SPEAK -> VoiceController.start(this)
            ACTION_STOP_SPEAK -> VoiceController.stopSpeaking()
            ACTION_LIVE -> openLive()
        }
        if (!Settings.canDrawOverlays(this)) {
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
        val d = resources.displayMetrics.density
        val coreSize = (56 * d).toInt()
        val coreView = OverlayCoreView(this)
        core = coreView
        val podView = buildPod(d)
        pod = podView
        podView.visibility = View.GONE
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(coreView, LinearLayout.LayoutParams(coreSize, coreSize))
            addView(podView)
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
            x = prefs.getInt("overlay_x", resources.displayMetrics.widthPixels - coreSize - (16 * d).toInt())
            y = prefs.getInt("overlay_y", resources.displayMetrics.heightPixels / 2)
        }
        params = lp
        coreView.setOnTouchListener(dragListener(box, lp))
        runCatching { window?.addView(box, lp) }
        scope.launch {
            JarvisEvents.assistantState.collect { state ->
                coreView.assistantState = state
                status?.text = statusFor(state)
            }
        }
    }

    private fun buildPod(d: Float): LinearLayout {
        val pad = (8 * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = androidx.core.content.ContextCompat.getDrawable(this@OverlayOrbService, R.drawable.bg_hud_panel)
            val label = TextView(this@OverlayOrbService).apply {
                text = "JARVIS · STANDBY"
                setTextColor(0xFF62E9F4.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 3
            }
            status = label
            addView(label)
            val row = LinearLayout(this@OverlayOrbService).apply {
                orientation = LinearLayout.HORIZONTAL
                val top = (6 * d).toInt()
                setPadding(0, top, 0, 0)
            }
            row.addView(chip("MIC") { VoiceController.start(this@OverlayOrbService) })
            row.addView(chip("STOP") { VoiceController.stopSpeaking() })
            row.addView(chip("LIVE") { openLive() })
            row.addView(chip("X") {
                remove()
                stopSelf()
            })
            addView(row)
        }
    }

    private fun chip(label: String, click: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            setTextColor(0xFF62E9F4.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = androidx.core.content.ContextCompat.getDrawable(this@OverlayOrbService, R.drawable.bg_fui_active)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = (4 * d).toInt()
            layoutParams = lp
            setOnClickListener {
                HudFeedback.tick(this)
                click()
            }
        }
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
            if (lp.x > screenW / 2) lp.x = screenW - view.width - margin
            runCatching { window?.updateViewLayout(view, lp) }
        }
    }

    private fun openLive() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch?.putExtra("open_live", true)
        if (launch != null) startActivity(launch)
    }

    private fun statusFor(state: AssistantState): String = when (state) {
        AssistantState.LISTENING -> "ASR · слушаю"
        AssistantState.THINKING, AssistantState.EXECUTING -> "LLM · думаю"
        AssistantState.SPEAKING -> "TTS · отвечаю"
        AssistantState.OFFLINE, AssistantState.ERROR -> "Ожидание · нажми MIC"
        else -> "WAKE · готов"
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
                NotificationChannel(channelId, "JARVIS overlay", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("JARVIS")
            .setContentText("Overlay orb")
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
        const val ACTION_SPEAK = "com.jarvis.assistant.OVERLAY_SPEAK"
        const val ACTION_STOP_SPEAK = "com.jarvis.assistant.OVERLAY_STOP_SPEAK"
        const val ACTION_LIVE = "com.jarvis.assistant.OVERLAY_LIVE"
        private const val NOTIFICATION_ID = 42

        fun show(context: Context) {
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
