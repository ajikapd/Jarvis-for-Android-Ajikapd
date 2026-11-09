package com.jarvis.app

import android.content.Context
import android.util.Log

/**
 * Защита от вылетов по кругу на чужих устройствах.
 *
 * Перед опасным нативным шагом (загрузка локальной модели, генерация) ставится метка,
 * после шага она снимается. Если процесс погиб посреди шага (нативный вылет, нехватка памяти),
 * метка остаётся, и при следующем запуске шаг отключается, а не роняет приложение снова.
 */
object NativeGuard {
    private const val TAG = "Jarvis.Guard"
    const val LLAMA = "llama"
    const val VOICE_AUTOSTART = "voice_autostart"
    private val STEPS = listOf(LLAMA, VOICE_AUTOSTART)

    private fun running(step: String) = "guard_running_$step"
    private fun off(step: String) = "guard_off_$step"

    fun <T> run(context: Context, step: String, block: () -> T): T {
        begin(context, step)
        try {
            return block()
        } finally {
            end(context, step)
        }
    }

    fun begin(context: Context, step: String) {
        JarvisSettings.prefs(context).edit().putBoolean(running(step), true).commit()
    }

    fun end(context: Context, step: String) {
        JarvisSettings.prefs(context).edit().remove(running(step)).commit()
    }

    fun isOff(context: Context, step: String): Boolean =
        JarvisSettings.prefs(context).getBoolean(off(step), false)

    /** Человек решил попробовать снова. */
    fun reset(context: Context, step: String) {
        JarvisSettings.prefs(context).edit().remove(off(step)).remove(running(step)).commit()
    }

    /** При запуске: шаги, на которых прошлый процесс погиб, отключаются и попадают в отчёт. */
    fun checkAfterStart(context: Context) {
        val prefs = JarvisSettings.prefs(context)
        // Автозапуск голоса держит метку дольше: на Android 11+ считаем это вылетом,
        // только если система подтвердила падение, а не человек закрыл приложение.
        val confirmed = CrashReports.lastExitWasCrash
        val died = STEPS.filter { prefs.getBoolean(running(it), false) }
            .filter { it != VOICE_AUTOSTART || confirmed != false }
        prefs.edit().remove(running(VOICE_AUTOSTART)).commit()
        if (died.isEmpty()) return
        val edit = prefs.edit()
        died.forEach { step ->
            edit.remove(running(step)).putBoolean(off(step), true)
            Log.w(TAG, "step $step killed the process last time, turned off")
            CrashReports.record(context, "шаг «$step» уронил приложение и отключён", IllegalStateException("guard:$step"))
        }
        edit.commit()
    }
}
