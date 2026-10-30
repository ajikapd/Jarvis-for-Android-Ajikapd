package com.jarvis.core.security

import android.util.Log

object KeyUtils {
    private const val TAG = "Jarvis.KeyUtils"

    fun normalizeProviderId(providerId: String): String {
        return providerId.trim().lowercase()
    }

    fun cleanKey(rawKey: String?): String {
        if (rawKey == null) return ""
        var cleaned = rawKey.trim()
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
            (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            if (cleaned.length >= 2) {
                cleaned = cleaned.substring(1, cleaned.length - 1).trim()
            }
        }
        return cleaned
    }

    fun isPlaceholderKey(key: String?): Boolean {
        val cleaned = cleanKey(key)
        if (cleaned.isBlank()) return true
        if (cleaned == "...") return true
        if (cleaned.contains("<") || cleaned.contains(">")) return true
        if (cleaned.length < 20) return true
        return false
    }
}
