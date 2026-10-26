package com.jarvis.app

import android.app.Application
import com.jarvis.app.companion.CompanionMemory
import com.jarvis.app.companion.SystemStateObserver
import com.jarvis.app.companion.UsageStatsObserver

/**
 * Корневой Application: perception + durable Companion Memory.
 */
class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemStateObserver.ensureStarted(this)
        UsageStatsObserver.ensureStarted(this)
        CompanionMemory.get(this).expireNow()
        // Ранний mmap chat-модели в RAM — к моменту первой фразы веса уже горячие.
        BrainGraph.preloadChatModel(this)
    }
}
