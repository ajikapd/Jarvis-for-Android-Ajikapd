package com.jarvis.app.companion

import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity

/**
 * Дешёвый наблюдатель системных broadcast'ов → [PhoneContextBus].
 * Без LLM, без autonomy. Stage 2 vertical slice.
 */
class SystemStateObserver(private val appContext: Context) {

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> emit(
                    ObservationType.SCREEN_ON,
                    summary = "Экран включён",
                )
                Intent.ACTION_SCREEN_OFF -> emit(
                    ObservationType.SCREEN_OFF,
                    summary = "Экран выключен",
                )
                Intent.ACTION_USER_PRESENT -> emit(
                    ObservationType.DEVICE_UNLOCKED,
                    summary = "Устройство разблокировано",
                )
                Intent.ACTION_POWER_CONNECTED -> emit(
                    ObservationType.CHARGER_CONNECTED,
                    summary = "Зарядка подключена",
                    entities = mapOf("charging" to "true"),
                )
                Intent.ACTION_POWER_DISCONNECTED -> emit(
                    ObservationType.CHARGER_DISCONNECTED,
                    summary = "Зарядка отключена",
                    entities = mapOf("charging" to "false"),
                )
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val pct = if (level >= 0) (level * 100) / scale else null
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    emit(
                        ObservationType.BATTERY_CHANGED,
                        summary = pct?.let { "Батарея $it%" } ?: "Батарея изменилась",
                        entities = buildMap {
                            if (pct != null) put("percent", pct.toString())
                            put("charging", charging.toString())
                        },
                    )
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    // Начальное значение приходит при регистрации: это состояние, а не событие.
                    val start = if (isInitialStickyBroadcast) mapOf(STARTUP to "true") else emptyMap()
                    if (state == 1) {
                        emit(
                            ObservationType.HEADPHONES_CONNECTED,
                            summary = "Наушники подключены",
                            entities = start,
                        )
                    } else {
                        emit(
                            ObservationType.HEADPHONES_DISCONNECTED,
                            summary = "Наушники отключены",
                            entities = start,
                        )
                    }
                }
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    val net = currentNetworkLabel()
                    emit(
                        ObservationType.NETWORK_CHANGED,
                        summary = "Сеть: $net",
                        entities = mapOf("network" to net),
                    )
                }
            }
        }
    }

    /**
     * Эти сообщения шлёт процесс Bluetooth, а не система, поэтому закрытому приёмнику они
     * не приходят. Подделать их нельзя: действия защищены, отправить может только Bluetooth.
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                BluetoothProfile.STATE_CONNECTED ->
                    emit(ObservationType.HEADPHONES_CONNECTED, summary = "Bluetooth-наушники подключены")
                BluetoothProfile.STATE_DISCONNECTED ->
                    emit(ObservationType.HEADPHONES_DISCONNECTED, summary = "Bluetooth-наушники отключены")
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        val bluetoothFilter = IntentFilter().apply {
            addAction(BLUETOOTH_A2DP_STATE)
            addAction(BLUETOOTH_HEADSET_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            appContext.registerReceiver(bluetoothReceiver, bluetoothFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(bluetoothReceiver, bluetoothFilter)
        }
        registered = true
        // Начальный снимок
        seedInitialState()
        Log.i(TAG, "SystemStateObserver started")
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        registered = false
        Log.i(TAG, "SystemStateObserver stopped")
    }

    private fun seedInitialState() {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm?.isCharging == true
        if (pct != null && pct >= 0) {
            emit(
                ObservationType.BATTERY_CHANGED,
                summary = "Батарея $pct% (старт)",
                entities = mapOf(
                    "percent" to pct.toString(),
                    "charging" to charging.toString(),
                ),
            )
        }
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        @Suppress("DEPRECATION")
        val wired = am?.isWiredHeadsetOn == true
        if (wired) {
            emit(
                ObservationType.HEADPHONES_CONNECTED,
                summary = "Наушники (старт)",
                entities = mapOf(STARTUP to "true"),
            )
        }
        val net = currentNetworkLabel()
        emit(
            ObservationType.NETWORK_CHANGED,
            summary = "Сеть: $net (старт)",
            entities = mapOf("network" to net),
        )
    }

    private fun currentNetworkLabel(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }

    private fun emit(
        type: ObservationType,
        summary: String,
        entities: Map<String, String> = emptyMap(),
    ) {
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.SYSTEM_BROADCAST,
                type = type,
                summary = summary,
                entities = entities,
                sensitivity = Sensitivity.PUBLIC,
                confidence = 1f,
            ),
        )
        Log.d(TAG, "obs ${type.name}: $summary")
    }

    companion object {
        private const val TAG = "Jarvis.Companion"
        private const val STARTUP = "startup"
        private const val BLUETOOTH_A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val BLUETOOTH_HEADSET_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
        @Volatile
        private var instance: SystemStateObserver? = null

        fun ensureStarted(context: Context): SystemStateObserver {
            val existing = instance
            if (existing != null) {
                existing.start()
                return existing
            }
            return synchronized(this) {
                instance ?: SystemStateObserver(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }
        }
    }
}
