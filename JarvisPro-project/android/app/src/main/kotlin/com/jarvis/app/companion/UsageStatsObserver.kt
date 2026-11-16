package com.jarvis.app.companion

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import com.jarvis.onboarding.PermissionsHelper

/**
 * Периодический опрос [UsageStatsManager] → [PhoneContextBus].
 * Backup к Accessibility для APP_FOREGROUND; без права — тихий no-op.
 */
class UsageStatsObserver(private val appContext: Context) {

    // Запрос статистики — системный вызов, иногда на секунды: не в главном потоке, иначе окно зависает.
    private val handler: Handler by lazy {
        Handler(android.os.HandlerThread("usage-stats").apply { start() }.looper)
    }
    private val pollIntervalMs = 12_000L

    @Volatile
    private var started = false

    @Volatile
    private var lastFgPackage: String? = null

    @Volatile
    private var lastEmitAtElapsed: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            pollOnce()
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    fun start() {
        if (started) return
        started = true
        handler.post(pollRunnable)
        Log.i(TAG, "UsageStatsObserver started")
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "UsageStatsObserver stopped")
    }

    private fun pollOnce() {
        if (!PermissionsHelper.isUsageAccessGranted(appContext)) return
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return
        val end = System.currentTimeMillis()
        val begin = end - 60_000L
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
        }.getOrNull().orEmpty()
        if (stats.isEmpty()) return
        val top = stats.maxByOrNull { it.lastTimeUsed } ?: return
        val pkg = top.packageName?.takeIf { it.isNotBlank() } ?: return
        if (pkg == lastFgPackage) return
        lastFgPackage = pkg
        lastEmitAtElapsed = SystemClock.elapsedRealtime()
        // Backup only: aggregator enforces a11y priority; skip obvious same-app early.
        val snap = PhoneContextBus.snapshot().context
        if (pkg == snap.currentApp) return
        if (snap.currentAppSource == ObservationSource.ACCESSIBILITY) {
            val age = System.currentTimeMillis() - snap.currentAppAtMillis
            if (age in 0 until 45_000L) return
        }
        val accepted = PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.USAGE_STATS,
                type = ObservationType.APP_FOREGROUND,
                packageName = pkg,
                summary = "UsageStats FG: $pkg",
                entities = mapOf(
                    "package" to pkg,
                    "last_used" to top.lastTimeUsed.toString(),
                ),
                sensitivity = Sensitivity.NORMAL,
                confidence = 0.7f,
            ),
        )
        if (accepted) Log.d(TAG, "APP_FOREGROUND $pkg")
        else Log.d(TAG, "dropped $pkg (aggregator)")
    }

    companion object {
        private const val TAG = "Jarvis.UsageStats"

        @Volatile
        private var instance: UsageStatsObserver? = null

        fun ensureStarted(context: Context): UsageStatsObserver {
            val existing = instance
            if (existing != null) {
                existing.start()
                return existing
            }
            return synchronized(this) {
                instance ?: UsageStatsObserver(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }
        }
    }
}
