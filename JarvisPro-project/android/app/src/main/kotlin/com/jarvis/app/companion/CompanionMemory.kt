package com.jarvis.app.companion

import android.content.Context
import com.jarvis.core.memory.CompanionMemoryService
import com.jarvis.core.memory.SqliteMemoryRepository

/** Process-wide Companion Memory (Stage 5.5). */
object CompanionMemory {
    @Volatile
    private var service: CompanionMemoryService? = null

    fun get(context: Context): CompanionMemoryService {
        service?.let { return it }
        return synchronized(this) {
            service ?: CompanionMemoryService(
                SqliteMemoryRepository(context.applicationContext),
            ).also { service = it }
        }
    }
}
