package com.jarvis.tools

import android.content.Context
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

/**
 * Яркость экрана.
 * Управляет системной яркостью (Settings.System.SCREEN_BRIGHTNESS) и
 * флагом автоматической яркости. Требует WRITE_SETTINGS (granted via adb).
 */
class BrightnessTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "brightness",
        name = "Яркость экрана",
        description = "Изменяет яркость экрана устройства",
        parameters = listOf(
            ToolParameter(
                name = "direction",
                description = "up — ярче, down — тусклее, max — максимум, set — заданный процент",
                type = ParameterType.ENUM,
                required = true,
                allowedValues = listOf("up", "down", "max", "set")
            ),
            ToolParameter(
                name = "percent",
                description = "целевая яркость 0-100",
                type = ParameterType.INTEGER,
                required = false
            )
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY
    )

    override fun execute(call: ToolCall): ToolResult {
        return try {
            val current = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
            )
            val max = 255
            val direction = call.arguments["direction"] as? String

            val target = when (direction) {
                "up" -> minOf(current + 30, max)
                "down" -> maxOf(current - 30, 20)
                "max" -> max
                "set" -> {
                    val pct = call.arguments["percent"] as? Number
                        ?: return ToolResult(
                            requestId = call.requestId, toolId = definition.id,
                            status = ToolResultStatus.AMBIGUOUS,
                            message = "Не указан уровень яркости в процентах."
                        )
                    ((pct.toInt() / 100f) * max).toInt().coerceIn(10, max)
                }
                else -> return ToolResult(
                    requestId = call.requestId, toolId = definition.id,
                    status = ToolResultStatus.AMBIGUOUS,
                    message = "Неизвестное направление: $direction"
                )
            }

            if (!Settings.System.canWrite(context)) {
                return ToolResult(
                    requestId = call.requestId, toolId = definition.id,
                    status = ToolResultStatus.DENIED,
                    message = "Нет права менять яркость. Выдайте разрешение через настройки или adb: pm grant com.jarvis.assistant android.permission.WRITE_SETTINGS"
                )
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target
            )

            ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.SUCCESS,
                message = "${(target * 100 / max)}%."
            )
        } catch (t: Throwable) {
            ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.FAILED,
                message = "Не удалось изменить яркость: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }
}