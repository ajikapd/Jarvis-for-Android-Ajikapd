package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Единая точка доступа к настройкам приложения.
 * Ключи используются DashboardActivity, VoiceEngine, BrainGraph и CoreTheme.
 */
object JarvisSettings {

    private const val PREFS = "jarvis_prefs"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)


    private fun secureKeys(context: Context): SecureKeyStore {
        SecureKeyMigration.migrate(context)
        return SecureKeyStore(context)
    }

    // ── Тема ──────────────────────────────────────────────────────
    val THEMES = listOf("CLASSIC", "ARC", "STEALTH", "LIGHT")
    fun theme(context: Context): String =
        prefs(context).getString("theme", "CLASSIC") ?: "CLASSIC"

    fun setTheme(context: Context, theme: String) {
        prefs(context).edit().putString("theme", theme).apply()
    }

    // ── Личность ──────────────────────────────────────────────────
    val PERSONAS = listOf("CLASSIC", "FRIENDLY", "MINIMAL", "DEVELOPER")
    fun persona(context: Context): String =
        prefs(context).getString("persona", "CLASSIC") ?: "CLASSIC"

    fun setPersona(context: Context, persona: String) {
        prefs(context).edit().putString("persona", persona).apply()
    }

    // ── Вейк-ворд ─────────────────────────────────────────────────
    fun wakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean("wake_enabled", true)

    fun setWakeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wake_enabled", enabled).apply()
    }

    /** Чувствительность вейк-ворда: low=0, medium=1, high=2. По умолчанию high. */
    fun wakeSensitivity(context: Context): Int =
        prefs(context).getInt("wake_sensitivity", 2)

    fun setWakeSensitivity(context: Context, value: Int) {
        prefs(context).edit().putInt("wake_sensitivity", value.coerceIn(0, 2)).apply()
    }

    // ── ИИ / Brain ────────────────────────────────────────────────
    /** Чувствительность Brain: чем выше, тем охотнее выполняет команды. */
    fun brainConfidenceThreshold(context: Context): Float =
        prefs(context).getFloat("brain_threshold", 0.6f)

    fun setBrainConfidenceThreshold(context: Context, value: Float) {
        prefs(context).edit().putFloat("brain_threshold", value.coerceIn(0.3f, 0.9f)).apply()
    }

    /** Использовать LLM Brain (LiteRT-LM) вместо детерминированного. */
    fun llmEnabled(context: Context): Boolean =
        prefs(context).getBoolean("llm_enabled", true)

    fun setLlmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("llm_enabled", enabled).apply()
    }

    // ── Прослушивание ─────────────────────────────────────────────
    fun adaptiveListening(context: Context): Boolean =
        prefs(context).getBoolean("adaptive_listening", true)

    fun lowPowerMode(context: Context): Boolean =
        prefs(context).getBoolean("low_power", false)

    fun setLowPowerMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("low_power", enabled).apply()
    }

    fun hudSfx(context: Context): Boolean =
        prefs(context).getBoolean("hud_sfx", false)

    fun setHudSfx(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("hud_sfx", enabled).apply()
    }

    // ── Память ───────────────────────────────────────────────────
    fun memoryEnabled(context: Context): Boolean =
        prefs(context).getBoolean("memory_enabled", true)

    fun setMemoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("memory_enabled", enabled).apply()
    }

    // ── Диалог LLM: скорость vs качество ─────────────────────────
    /** FAST | BALANCED (по умолчанию) | QUALITY */
    val CHAT_PROFILES = listOf("FAST", "BALANCED", "QUALITY")

    fun chatProfile(context: Context): String =
        prefs(context).getString("chat_profile", "BALANCED") ?: "BALANCED"

    fun setChatProfile(context: Context, profile: String) {
        val p = if (profile in CHAT_PROFILES) profile else "BALANCED"
        prefs(context).edit().putString("chat_profile", p).apply()
    }

    /** Держать chat-модель в RAM с старта приложения (рекомендуется). */
    fun chatPreloadEnabled(context: Context): Boolean =
        prefs(context).getBoolean("chat_preload", true)

    fun setChatPreloadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("chat_preload", enabled).apply()
    }

    // ── Cloud LLM (NaraRouter) ────────────────────────────────────
    /** Encrypted provider key for the legacy custom LLM endpoint. */
    fun cloudApiKey(context: Context): String =
        secureKeys(context).getKey("custom").orEmpty()

    fun setCloudApiKey(context: Context, key: String) {
        secureKeys(context).setKey("custom", key)
    }

    fun cloudBaseUrl(context: Context): String =
        prefs(context).getString("cloud_base_url", null)?.trim().orEmpty()

    fun setCloudBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString("cloud_base_url", url.trim()).apply()
    }

    /** Использовать cloud при наличии ключа и сети (default true). */
    fun preferCloudLlm(context: Context): Boolean =
        prefs(context).getBoolean("prefer_cloud_llm", true)

    fun setPreferCloudLlm(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("prefer_cloud_llm", enabled).apply()
    }

    fun routerMode(context: Context): String =
        prefs(context).getString("router_mode", null)
            ?: if (preferCloudLlm(context)) "HYBRID" else "LOCAL"

    fun cycleRouterMode(context: Context): String {
        val order = listOf("HYBRID", "LOCAL", "CLOUD")
        val cur = routerMode(context)
        val next = order[((order.indexOf(cur).takeIf { it >= 0 } ?: -1) + 1) % order.size]
        prefs(context).edit().putString("router_mode", next).apply()
        setPreferCloudLlm(context, next != "LOCAL")
        return next
    }

    fun cloudModelForProfile(context: Context): String {
        return when (chatProfile(context)) {
            "QUALITY" -> com.jarvis.brain.aiprovider.NaraRouterDefaults.MODEL_QUALITY
            else -> com.jarvis.brain.aiprovider.NaraRouterDefaults.MODEL_FAST
        }
    }

    fun cloudConfigured(context: Context): Boolean {
        val keys = secureKeys(context)
        return keys.hasKey("custom") || keys.hasKey("groq") || keys.hasKey("gemini")
    }

    // ── Cloud / Hybrid ASR (V2-08) ─────────────────────────────────
    /** AUTO | CLOUD_PREFERRED | LOCAL_ONLY */
    fun asrMode(context: Context): String =
        prefs(context).getString("asr_mode", "AUTO")?.trim()?.uppercase().orEmpty().ifBlank { "AUTO" }

    fun setAsrMode(context: Context, mode: String) {
        val m = mode.trim().uppercase().let {
            if (it in setOf("AUTO", "CLOUD_PREFERRED", "LOCAL_ONLY")) it else "AUTO"
        }
        prefs(context).edit().putString("asr_mode", m).apply()
    }

    /**
     * Optional Whisper-compatible base URL (…/v1). Empty → use Android SpeechRecognizer cloud.
     * Never confused with Nara chat URL unless user explicitly sets a Whisper host.
     */
    fun asrCloudBaseUrl(context: Context): String =
        prefs(context).getString("asr_cloud_base_url", "")?.trim().orEmpty()

    fun setAsrCloudBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString("asr_cloud_base_url", url.trim()).apply()
    }

    /**
     * ASR API key. If blank and [asrCloudBaseUrl] is set, falls back to [cloudApiKey]
     * (shared secret storage — key never logged).
     */
    fun asrCloudApiKey(context: Context): String {
        val keys = secureKeys(context)
        val dedicated = keys.getKey("custom_asr").orEmpty()
        if (dedicated.isNotBlank()) return dedicated
        return if (asrCloudBaseUrl(context).isNotBlank()) cloudApiKey(context) else ""
    }

    fun setAsrCloudApiKey(context: Context, key: String) {
        secureKeys(context).setKey("custom_asr", key)
    }

    fun asrCloudModel(context: Context): String {
        val v = prefs(context).getString("asr_cloud_model", null)?.trim().orEmpty()
        return v.ifBlank { "whisper-1" }
    }

    fun setAsrCloudModel(context: Context, model: String) {
        prefs(context).edit().putString("asr_cloud_model", model.trim()).apply()
    }

    // ── Cloud / Hybrid TTS (V2-09) ─────────────────────────────────
    /** AUTO | CLOUD_PREFERRED | LOCAL_ONLY */
    fun ttsMode(context: Context): String =
        prefs(context).getString("tts_mode", "AUTO")?.trim()?.uppercase().orEmpty().ifBlank { "AUTO" }

    fun setTtsMode(context: Context, mode: String) {
        val m = mode.trim().uppercase().let {
            if (it in setOf("AUTO", "CLOUD_PREFERRED", "LOCAL_ONLY")) it else "AUTO"
        }
        prefs(context).edit().putString("tts_mode", m).apply()
    }

    fun ttsCloudBaseUrl(context: Context): String {
        val dedicated = prefs(context).getString("tts_cloud_base_url", "")?.trim().orEmpty()
        if (dedicated.isNotBlank()) return dedicated
        // Reuse ASR Whisper host if user already configured OpenAI-compatible audio
        return asrCloudBaseUrl(context)
    }

    fun setTtsCloudBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString("tts_cloud_base_url", url.trim()).apply()
    }

    fun ttsCloudApiKey(context: Context): String {
        val keys = secureKeys(context)
        val dedicated = keys.getKey("custom_tts").orEmpty()
        if (dedicated.isNotBlank()) return dedicated
        if (ttsCloudBaseUrl(context).isNotBlank()) {
            val asrKey = keys.getKey("custom_asr").orEmpty()
            if (asrKey.isNotBlank()) return asrKey
            return cloudApiKey(context)
        }
        return ""
    }

    fun setTtsCloudApiKey(context: Context, key: String) {
        secureKeys(context).setKey("custom_tts", key)
    }

    fun ttsCloudModel(context: Context): String {
        val v = prefs(context).getString("tts_cloud_model", null)?.trim().orEmpty()
        return v.ifBlank { "tts-1" }
    }

    fun setTtsCloudModel(context: Context, model: String) {
        prefs(context).edit().putString("tts_cloud_model", model.trim()).apply()
    }

    fun ttsCloudVoice(context: Context): String {
        val v = prefs(context).getString("tts_cloud_voice", null)?.trim().orEmpty()
        return v.ifBlank { "alloy" }
    }

    fun setTtsCloudVoice(context: Context, voice: String) {
        prefs(context).edit().putString("tts_cloud_voice", voice.trim()).apply()
    }

    // ── Безопасность ──────────────────────────────────────────────
    fun confirmSensitive(context: Context): Boolean =
        prefs(context).getBoolean("confirm_sensitive", true)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}