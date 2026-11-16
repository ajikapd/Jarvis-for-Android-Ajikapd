package com.jarvis.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Сторож главного потока. Если окно не отвечает дольше [STALL_MS], записывает,
 * на чём застрял главный поток: так в отчёт попадает зависание, даже когда человек
 * сам останавливает приложение и Android не успевает зафиксировать ANR.
 * Один отчёт на процесс, чтобы не засорять папку.
 */
object MainThreadWatchdog {
    private const val TAG = "Jarvis.Watchdog"
    private const val TICK_MS = 1_000L
    private const val STALL_MS = 5_000L

    @Volatile private var lastPong = SystemClock.uptimeMillis()
    @Volatile private var started = false
    private var reported = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val pong = Runnable { lastPong = SystemClock.uptimeMillis() }
        Thread({
            while (true) {
                main.post(pong)
                try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { return@Thread }
                val stalled = SystemClock.uptimeMillis() - lastPong
                if (stalled >= STALL_MS && !reported) {
                    reported = true
                    val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }
                    Log.w(TAG, "main thread stalled ${stalled}ms\n$stack")
                    CrashReports.recordStall(app, stalled, stack)
                }
            }
        }, "jarvis-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
