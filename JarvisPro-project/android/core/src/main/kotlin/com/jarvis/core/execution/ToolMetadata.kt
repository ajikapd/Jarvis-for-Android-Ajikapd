package com.jarvis.core.execution

/**
 * Stage 8E: наблюдаемые метаданные Tool / execution.
 *
 * Не повышает autonomy, не меняет risk, не обходит Policy/SafetyGate,
 * не разрешает выполнение.
 */
data class ToolMetadata(
    val tags: Set<String> = emptySet(),
    val source: String = "native",
    val notes: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
) {
    init {
        require(source.length <= MAX_SOURCE) { "metadata.source too long" }
        require(tags.size <= MAX_TAGS) { "too many metadata tags" }
        require(notes.size <= MAX_NOTES) { "too many metadata notes" }
        require(extras.size <= MAX_EXTRAS) { "too many metadata extras" }
        extras.forEach { (k, v) ->
            require(k.length <= MAX_KEY) { "metadata extra key too long" }
            require(v.length <= MAX_VALUE) { "metadata extra value too long" }
            val lk = k.lowercase()
            require(lk !in FORBIDDEN_SECURITY_KEYS) {
                "ToolMetadata must not carry security override key: $k"
            }
        }
        tags.forEach { t ->
            require(t.length <= MAX_KEY) { "metadata tag too long" }
            require(t.lowercase() !in FORBIDDEN_SECURITY_KEYS) {
                "ToolMetadata tag must not override security: $t"
            }
        }
    }

    companion object {
        const val MAX_SOURCE = 32
        const val MAX_TAGS = 16
        const val MAX_NOTES = 8
        const val MAX_EXTRAS = 12
        const val MAX_KEY = 48
        const val MAX_VALUE = 96

        val FORBIDDEN_SECURITY_KEYS: Set<String> = setOf(
            "autonomy_level",
            "force_allow",
            "bypass_gate",
            "bypass_policy",
            "risk_class",
            "verdict",
            "gate_status",
            "integrity_fingerprint",
            "allow",
            "deny",
        )
    }
}
