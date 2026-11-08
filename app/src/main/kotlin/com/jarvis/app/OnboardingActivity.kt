package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.onboarding.PermissionsHelper
import com.jarvis.screencontrol.JarvisAccessibilityService

/**
 * Онбординг Stage 1: микрофон, уведомления, оверлей, батарея, автозапуск,
 * Accessibility, Notification Listener, WRITE_SETTINGS + runtime для звонков/контактов/SMS.
 */
class OnboardingActivity : AppCompatActivity() {

    private val audioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUi() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUi() }

    private val runtimeBatchLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        setupButtons()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnRecordAudio).setOnClickListener {
            audioLauncher.launch(PermissionsHelper.audioRecordPermission())
        }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            PermissionsHelper.postNotificationsPermission()?.let { notificationLauncher.launch(it) }
                ?: refreshUi()
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            PermissionsHelper.openOverlaySettings(this)
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            PermissionsHelper.openBatteryOptimizationSettings(this)
        }
        findViewById<Button>(R.id.btnAutoStart).setOnClickListener {
            // HyperOS не отдаёт статус автозапуска — фиксируем визит до открытия экрана.
            prefs().edit().putBoolean(PREF_AUTOSTART_VISITED, true).apply()
            refreshUi()
            PermissionsHelper.openAutoStartSettings(this)
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            PermissionsHelper.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.btnNotificationListener).setOnClickListener {
            PermissionsHelper.openNotificationListenerSettings(this)
        }
        findViewById<Button>(R.id.btnWriteSettings).setOnClickListener {
            PermissionsHelper.openWriteSettings(this)
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestExtraRuntime()
            prefs().edit()
                .putBoolean(PREF_ONBOARDING_DONE, true)
                .putBoolean(PREF_AUTOSTART_VISITED, true)
                .apply()
            VoiceController.start(this)
            // Дальше: выбор интерфейса, гид, сам интерфейс.
            UiMode.continueFrom(this)
        }
        if (BuildConfig.DEBUG) {
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.stepDebugConsole)
                .visibility = android.view.View.VISIBLE
            findViewById<Button>(R.id.btnDebugConsole).setOnClickListener {
                startActivity(Intent(this, TestConsoleActivity::class.java))
            }
        }
    }

    private fun requestExtraRuntime() {
        val needed = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            runtimeBatchLauncher.launch(needed.toTypedArray())
        }
    }

    private fun refreshUi() {
        val audioOk = PermissionsHelper.isAudioRecordGranted(this)
        val notifOk = PermissionsHelper.isPostNotificationsGranted(this)
        val overlayOk = PermissionsHelper.canDrawOverlays(this)
        val batteryOk = PermissionsHelper.isIgnoringBatteryOptimizations(this)
        val a11yOk = PermissionsHelper.isAccessibilityServiceEnabled(
            this,
            JarvisAccessibilityService::class.java,
        )
        val nlsOk = PermissionsHelper.isNotificationListenerEnabled(this)
        val writeOk = PermissionsHelper.canWriteSettings(this)

        setStatus(R.id.stepRecordAudio, R.id.btnRecordAudio, audioOk)
        setStatus(R.id.stepNotifications, R.id.btnNotifications, notifOk)
        setStatus(R.id.stepOverlay, R.id.btnOverlay, overlayOk)
        setStatus(R.id.stepBattery, R.id.btnBattery, batteryOk)
        // HyperOS не даёт проверить автозапуск программно — после открытия экрана считаем настроенным.
        val autostartOk = prefs().getBoolean(PREF_AUTOSTART_VISITED, false)
        setStatus(R.id.stepAutoStart, R.id.btnAutoStart, autostartOk)
        setStatus(R.id.stepAccessibility, R.id.btnAccessibility, a11yOk)
        setStatus(R.id.stepNotificationListener, R.id.btnNotificationListener, nlsOk)
        setStatus(R.id.stepWriteSettings, R.id.btnWriteSettings, writeOk)

        // Критичные: микрофон + уведомления. Остальное — желательно, но не блокирует Dashboard.
        val criticalsOk = audioOk && notifOk
        findViewById<Button>(R.id.btnStart).isEnabled = criticalsOk || BuildConfig.DEBUG
    }

    private fun setStatus(cardId: Int, btnId: Int, ok: Boolean) {
        val card = findViewById<com.google.android.material.card.MaterialCardView>(cardId)
        val btn = findViewById<Button>(btnId)
        if (ok) {
            card.strokeColor = ContextCompat.getColor(this, R.color.status_ok)
            card.strokeWidth = 3
            btn.text = "Готово"
            btn.isEnabled = false
        } else {
            card.strokeColor = ContextCompat.getColor(this, R.color.jarvis_primary)
            card.strokeWidth = 2
            btn.isEnabled = true
            btn.text = "Настроить"
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val PREFS = "jarvis_prefs"
        const val PREF_ONBOARDING_DONE = "onboarding_done_v1"
        const val PREF_AUTOSTART_VISITED = "autostart_visited_v1"

        /** Только первый запуск — не держим пользователя на онбординге вечно. */
        fun needsOnboarding(activity: AppCompatActivity): Boolean {
            val prefs = activity.getSharedPreferences(PREFS, MODE_PRIVATE)
            return !prefs.getBoolean(PREF_ONBOARDING_DONE, false)
        }
    }
}
