package com.jarvis.tools

import android.content.Context
import android.media.AudioManager
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
 * Инструмент «Громкость»: меняет громкость мультимедиа.
 * SAFE, локально, без специальных разрешений (AudioManager не требует).
 */
class VolumeTool(private val context: Context) : Tool {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val definition = ToolDefinition(
        id = "volume",
        name = "Громкость",
        description = "Меняет громкость мультимедиа: громче, тише, без звука, на максимум или на заданный процент",
        parameters = listOf(
            ToolParameter(
                name = "direction",
                description = "up — громче, down — тише, mute — без звука, unmute — со звуком, max — максимум, set — заданный процент",
                type = ParameterType.ENUM,
                required = true,
                allowedValues = listOf("up", "down", "mute", "unmute", "max", "set")
            ),
            ToolParameter(
                name = "percent",
                description = "целевой уровень громкости 0-100",
                type = ParameterType.INTEGER,
                required = false
            )
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY
    )

    override fun execute(call: ToolCall): ToolResult {
        val direction = (call.arguments["direction"] ?: call.arguments["action"]) as? String
        return try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            when (direction) {
                "up" -> {
                    val target = minOf(current + 1, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    succes(call, "Громче.")
                }
                "down" -> {
                    val target = maxOf(current - 1, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    succes(call, "Тише.")
                }
                "mute" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    succes(call, "Без звука.")
                }
                "unmute" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.5).toInt(), 0)
                    succes(call, "Звук 50%.")
                }
                "max" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                    succes(call, "Максимум.")
                }
                "set" -> {
                    val percent = call.arguments["percent"] as? Number ?: return ToolResult(
                        requestId = call.requestId, toolId = definition.id,
                        status = ToolResultStatus.AMBIGUOUS,
                        message = "Какой процент громкости?"
                    )
                    val target = ((percent.toInt() / 100f) * max).toInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    succes(call, "${percent.toInt()}%.")
                }
                else -> ToolResult(
                    requestId = call.requestId, toolId = definition.id,
                    status = ToolResultStatus.AMBIGUOUS,
                    message = "Неизвестное направление: $direction"
                )
            }
        } catch (t: Throwable) {
            ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.FAILED,
                message = "Не удалось изменить громкость: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    private fun percent(max: Int, value: Int): Int =
        if (max <= 0) 0 else (value * 100 / max).coerceIn(0, 100)

    private fun succes(call: ToolCall, message: String): ToolResult = ToolResult(
        requestId = call.requestId,
        toolId = definition.id,
        status = ToolResultStatus.SUCCESS,
        message = message
    )
}