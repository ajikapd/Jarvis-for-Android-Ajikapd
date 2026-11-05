package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.SystemSnapshot
import com.jarvis.core.nlp.SystemStatusReport

/** Отчёт о состоянии телефона: заряд, сеть, память, температура. */
class SystemStatusTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "system.status",
        name = "Состояние систем",
        description = "Заряд батареи, сеть, свободная память и температура телефона",
        parameters = emptyList(),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult = try {
        ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, SystemStatusReport.format(snapshot()))
    } catch (t: Throwable) {
        ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Не удалось снять показания: ${t.message}")
    }

    private fun snapshot(): SystemSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = (battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }
        return SystemSnapshot(
            batteryPercent = level * 100 / scale,
            charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
                state == BatteryManager.BATTERY_STATUS_FULL,
            network = network(),
            freeStorageGb = (StatFs(Environment.getDataDirectory().path).availableBytes / BYTES_IN_GB).toInt(),
            batteryTempC = temp?.div(10),
        )
    }

    private fun network(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "есть"
        }
    }

    private companion object {
        const val BYTES_IN_GB = 1024L * 1024L * 1024L
    }
}
