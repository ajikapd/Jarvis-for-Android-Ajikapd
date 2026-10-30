package com.jarvis.app

import android.app.Application
import android.util.Log
import com.jarvis.app.companion.CompanionMemory
import com.jarvis.app.companion.SystemStateObserver
import com.jarvis.app.companion.UsageStatsObserver
import com.jarvis.core.storage.ConversationJournal
import com.jarvis.core.storage.RetentionPolicy

/**
 * Корневой Application: perception + durable Companion Memory + постоянная
 * память Jarvis (`jarvis_core.db`).
 */
class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SecureKeyMigration.migrate(this)
        SystemStateObserver.ensureStarted(this)
        UsageStatsObserver.ensureStarted(this)
        CompanionMemory.get(this).expireNow()

        // Постоянная память: журнал диалога, чистка старых записей,
        // восстановление пузырей UI (в фоне — главный поток не ждёт SQLite).
        ConversationJournal.install(this)
        ConversationJournal.expire()
        restoreUiHistory()

        // Ранний mmap chat-модели в RAM — к моменту первой фразы веса уже горячие.
        BrainGraph.preloadChatModel(this)
    }

    private fun restoreUiHistory() {
        Thread({
            val startedAt = System.currentTimeMillis()
            runCatching {
                val entries = ConversationJournal.recent(
                    limit = RetentionPolicy.STORE_RECENT_LIMIT,
                    maxAgeMs = RetentionPolicy.CONVERSATION_TTL_MS,
                )
                ConversationStore.restore(entries)
                Log.i(
                    "Jarvis.ChatHistory",
                    "ui restore n=${entries.size} in ${System.currentTimeMillis() - startedAt}ms",
                )
            }.onFailure { Log.w("Jarvis.ChatHistory", "ui restore failed: ${it.message}") }
        }, "chat-history-restore").start()
    }
}
