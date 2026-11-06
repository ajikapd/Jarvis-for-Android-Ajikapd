package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
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

    /** Время срабатывания активного таймера, 0 — таймера нет. */
    @Volatile private var endsAtMs = 0L

    @Volatile private var stopwatchStartedAt = 0L

    private val endRunnable = Runnable {
        endsAtMs = 0L
        vibrate(3000L)
        ring()
        Log.d(TAG, "timer finished")
    }

    override fun execute(call: ToolCall): ToolResult {
        when (call.arguments["action"]?.toString()) {
            "cancel" -> return cancel(call)
            "status" -> return status(call)
            "alarm" -> return setAlarm(call)
            "alarm_cancel" -> return cancelAlarm(call)
            "stopwatch" -> return stopwatch(call)
        }
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
        endsAtMs = SystemClock.elapsedRealtime() + totalMs

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

    private fun cancel(call: ToolCall): ToolResult {
        val had = endsAtMs != 0L
        handler.removeCallbacks(endRunnable)
        endsAtMs = 0L
        return done(call, if (had) "Таймер отменён." else "Таймер не запущен.")
    }

    private fun status(call: ToolCall): ToolResult {
        val left = endsAtMs - SystemClock.elapsedRealtime()
        if (endsAtMs == 0L || left <= 0) return done(call, "Таймер не запущен.")
        val seconds = (left + 999) / 1000
        val text = if (seconds >= 60) "${seconds / 60} мин ${seconds % 60} сек" else "$seconds сек"
        return done(call, "Осталось $text.")
    }

    /** Системный будильник: звонит и после смерти процесса, в отличие от таймера. */
    private fun setAlarm(call: ToolCall): ToolResult {
        val hour = (call.arguments["hour"] as? Number)?.toInt()
        val minute = (call.arguments["minute"] as? Number)?.toInt() ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "На какое время?")
        }
        return try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            done(call, "Будильник на %d:%02d.".format(hour, minute))
        } catch (t: Throwable) {
            Log.w(TAG, "alarm failed: ${t.message}", t)
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Не удалось поставить будильник.")
        }
    }

    /** Системные часы сами спросят, какой будильник снять, если их несколько. */
    private fun cancelAlarm(call: ToolCall): ToolResult = try {
        context.startActivity(
            Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        done(call, "Снимаю будильник, сэр.")
    } catch (t: Throwable) {
        Log.w(TAG, "alarm cancel failed: ${t.message}", t)
        ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Не удалось снять будильник.")
    }

    private fun stopwatch(call: ToolCall): ToolResult {
        val mode = call.arguments["mode"]?.toString()
        if (mode == "start") {
            stopwatchStartedAt = SystemClock.elapsedRealtime()
            return done(call, "Секундомер запущен, сэр.")
        }
        val started = stopwatchStartedAt
        if (started == 0L) return done(call, "Секундомер не запущен, сэр.")
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000
        if (mode == "stop") stopwatchStartedAt = 0L
        val time = if (seconds >= 60) "${seconds / 60} мин ${seconds % 60} сек" else "$seconds сек"
        return done(call, if (mode == "stop") "Прошло $time, сэр." else "Идёт $time, сэр.")
    }

    private fun done(call: ToolCall, message: String) =
        ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, message)

    private fun ring() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        val ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return
        runCatching { ringtone.play() }
        handler.postDelayed({ runCatching { ringtone.stop() } }, RING_MS)
    }

    private fun vibrate(durationMs: Long) {
        val v = vibrator ?: return
        val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        runCatching { v.vibrate(effect) }
            .onFailure { Log.w(TAG, "vibrate failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "Jarvis.Tools"
        private const val RING_MS = 6_000L
    }
}