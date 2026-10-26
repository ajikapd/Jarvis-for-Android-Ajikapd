package com.jarvis.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
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
 * Таймер. Запускает обратный отсчёт и вибрирует по истечении.
 * SAFE, локально, без разрешений. Таймер сбрасывается при перезапуске сервиса.
 */
class TimerTool(private val context: Context) : Tool {

    private val handler = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override val definition = ToolDefinition(
        id = "timer",
        name = "Таймер",
        description = "Запускает таймер обратного отсчёта на заданное количество минут/секунд",
        parameters = listOf(
            ToolParameter(
                name = "minutes",
                description = "минуты",
                type = ParameterType.INTEGER,
                required = false
            ),
            ToolParameter(
                name = "seconds",
                description = "секунды",
                type = ParameterType.INTEGER,
                required = false
            )
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY
    )

    private val endRunnable = Runnable {
        vibrate(3000L)
        Log.d(TAG, "timer finished")
    }

    override fun execute(call: ToolCall): ToolResult {
        val minutes = (call.arguments["minutes"] as? Number)?.toInt() ?: 0
        val seconds = (call.arguments["seconds"] as? Number)?.toInt() ?: 0
        val totalMs = (minutes * 60L + seconds) * 1000L
        if (totalMs <= 0) {
            return ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.AMBIGUOUS,
                message = "Не указано время таймера."
            )
        }

        handler.removeCallbacks(endRunnable)
        handler.postDelayed(endRunnable, totalMs)

        val label = if (minutes > 0) {
            if (seconds > 0) "$minutes мин $seconds сек" else "$minutes мин"
        } else {
            "$seconds сек"
        }
        return ToolResult(
            requestId = call.requestId,
            toolId = definition.id,
            status = ToolResultStatus.SUCCESS,
            message = "Таймер: $label.",
        )
    }

    private fun vibrate(durationMs: Long) {
        val v = vibrator ?: return
        val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        runCatching { v.vibrate(effect) }
    }

    companion object {
        private const val TAG = "Jarvis.Tools"
    }
}