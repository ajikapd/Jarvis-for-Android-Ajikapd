package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.screencontrol.ActionCoordinator
import com.jarvis.screencontrol.ScreenController

/**
 * Системные настройки: wifi / bluetooth / airplane.
 * Где публичный API ограничен (Android 10+), открываем панель настроек
 * и при возможности кликаем тумблер через Accessibility.
 */
class SystemSettingTool(private val context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "system.setting",
        name = "Системная настройка",
        description = "Включает или выключает Wi‑Fi, Bluetooth или режим полёта; либо открывает соответствующий экран настроек",
        parameters = listOf(
            ToolParameter(
                "name",
                "wifi, bluetooth или airplane",
                ParameterType.ENUM,
                required = true,
                allowedValues = listOf("wifi", "bluetooth", "airplane"),
            ),
            ToolParameter(
                "value",
                "on, off или open",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("on", "off", "open"),
            ),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val name = call.arguments["name"]?.toString()?.lowercase().orEmpty()
        val value = call.arguments["value"]?.toString()?.lowercase() ?: "open"
        return when (name) {
            "wifi" -> handleWifi(call.requestId, value)
            "bluetooth" -> handleBluetooth(call.requestId, value)
            "airplane", "flight", "aero" -> handleAirplane(call.requestId, value)
            in SECTIONS -> openSection(call.requestId, SECTIONS.getValue(name))
            else -> ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.AMBIGUOUS,
                "Неизвестная настройка «$name». Доступны: wifi, bluetooth, airplane.",
            )
        }
    }

    private fun openSection(requestId: String, action: String): ToolResult = try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, "Открыл настройки.")
    } catch (t: Throwable) {
        ToolResult(requestId, definition.id, ToolResultStatus.FAILED, t.message)
    }

    private fun handleWifi(requestId: String, value: String): ToolResult {
        if (value == "open" || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = coordinator.openWifiSettings()
            if (value == "on" || value == "off") {
                Thread.sleep(800)
                // Best-effort toggle via a11y after opening panel
                if (ScreenController.isAvailable()) {
                    coordinator.findAndClick("Wi‑Fi")
                    Thread.sleep(300)
                    // Many OEMs use a switch without stable text — try common labels
                    listOf("Использовать Wi‑Fi", "Wi-Fi", "Вкл", "On").forEach {
                        coordinator.findAndClick(it)
                    }
                }
            }
            return ToolResult(
                requestId,
                definition.id,
                if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                when (value) {
                    "on" -> "Открыл Wi‑Fi. Включи вручную, если тумблер не переключился."
                    "off" -> "Открыл Wi‑Fi. Выключи вручную, если тумблер не переключился."
                    else -> r.message
                },
            )
        }
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ok = wm.setWifiEnabled(value == "on")
        return ToolResult(
            requestId,
            definition.id,
            if (ok) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            if (ok) "Wi‑Fi ${if (value == "on") "включён" else "выключен"}." else "Не удалось переключить Wi‑Fi.",
        )
    }

    private fun handleBluetooth(requestId: String, value: String): ToolResult {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if ((value == "on" || value == "off") && ScreenController.isAvailable()) {
                Thread.sleep(800)
                coordinator.findAndClick("Bluetooth")
            }
            ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, "Открыл настройки Bluetooth.")
        } catch (t: Throwable) {
            ToolResult(requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private fun handleAirplane(requestId: String, value: String): ToolResult {
        val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult(
                requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                "Открыл настройки режима полёта" +
                    if (value == "on" || value == "off") " — переключи тумблер." else ".",
            )
        } catch (t: Throwable) {
            ToolResult(requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private companion object {
        val SECTIONS = mapOf(
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "notifications" to Settings.ACTION_NOTIFICATION_ASSISTANT_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
        )
    }
}
