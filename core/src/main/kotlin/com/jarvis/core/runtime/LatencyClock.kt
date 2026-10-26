package com.jarvis.core.runtime

/**
 * V2-06: monotonic ms clock for latency marks.
 * Prefers [android.os.SystemClock.elapsedRealtime] on device; falls back to nanoTime on JVM tests.
 */
object LatencyClock {
    fun elapsedMs(): Long =
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
}
