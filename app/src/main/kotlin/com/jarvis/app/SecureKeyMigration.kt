package com.jarvis.app

import android.content.Context
import com.jarvis.core.llm.CloudKeyValidator

/** One-time migration from legacy plaintext cloud keys in jarvis_prefs. */
object SecureKeyMigration {
    private const val MIGRATION_FLAG = "migrated_keys_v1"
    private val legacyKeys = listOf(
        "cloud_api_key" to "custom",
        "asr_cloud_api_key" to "custom_asr",
        "tts_cloud_api_key" to "custom_tts",
    )
    private val lock = Any()

    fun migrate(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val prefs = JarvisSettings.prefs(app)
            if (prefs.getBoolean(MIGRATION_FLAG, false)) return

            val store = SecureKeyStore(app)
            var complete = true
            for ((legacyName, providerId) in legacyKeys) {
                val legacyValue = runCatching {
                    prefs.getString(legacyName, null).orEmpty()
                }.getOrDefault("")
                if (legacyValue.isBlank()) continue

                val clean = CloudKeyValidator.sanitizeKey(legacyValue)
                if (!CloudKeyValidator.isStubKey(clean)) {
                    store.setKey(providerId, clean)
                    if (!store.hasKey(providerId)) {
                        complete = false
                        continue
                    }
                }
                prefs.edit().remove(legacyName).apply()
            }
            if (complete) prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
        }
    }

    fun isMigrated(context: Context): Boolean =
        JarvisSettings.prefs(context.applicationContext).getBoolean(MIGRATION_FLAG, false)
}
