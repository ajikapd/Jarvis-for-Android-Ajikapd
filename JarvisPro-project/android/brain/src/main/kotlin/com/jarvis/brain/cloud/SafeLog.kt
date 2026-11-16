package com.jarvis.brain.cloud

/**
 * JVM-безопасный лог: на Android пишет в android.util.Log,
 * в unit-тестах (метод Log не мокается) — тихо проглатывает.
 */
internal object SafeLog {
    fun i(tag: String, msg: String) {
        try { android.util.Log.i(tag, msg) } catch (_: Throwable) { }
    }

    fun w(tag: String, msg: String) {
        try { android.util.Log.w(tag, msg) } catch (_: Throwable) { }
    }
}
