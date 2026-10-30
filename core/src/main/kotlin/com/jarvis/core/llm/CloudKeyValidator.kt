package com.jarvis.core.llm

import java.util.Base64

/** Pure validation and envelope helpers shared by Android settings and JVM tests. */
object CloudKeyValidator {
    fun normalizeProviderId(id: String?): String = id?.trim()?.lowercase().orEmpty()

    /** Removes surrounding whitespace/quotes and line breaks without logging the value. */
    fun sanitizeKey(key: String?): String {
        if (key == null) return ""
        var cleaned = key.trim().replace("\r", "").replace("\n", "").trim()
        if (cleaned.length >= 2 &&
            ((cleaned.startsWith('"') && cleaned.endsWith('"')) ||
                (cleaned.startsWith('\'') && cleaned.endsWith('\'')))
        ) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        return cleaned.replace("\r", "").replace("\n", "").trim()
    }

    fun isStubKey(key: String?): Boolean {
        val sanitized = sanitizeKey(key)
        return sanitized.isBlank() ||
            sanitized == "..." ||
            sanitized.contains('<') ||
            sanitized.contains('>') ||
            sanitized.length < 20
    }

    fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): String =
        "${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(ciphertext)}"

    fun decodeEnvelope(value: String?): Pair<ByteArray, ByteArray>? {
        if (value.isNullOrBlank()) return null
        val separator = value.indexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return null
        return runCatching {
            val iv = Base64.getDecoder().decode(value.substring(0, separator))
            val ciphertext = Base64.getDecoder().decode(value.substring(separator + 1))
            if (iv.isEmpty() || ciphertext.isEmpty()) null else iv to ciphertext
        }.getOrNull()
    }
}

