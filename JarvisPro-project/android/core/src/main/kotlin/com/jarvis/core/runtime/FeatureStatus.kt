package com.jarvis.core.runtime

/**
 * V2-01: честный lifecycle фичи.
 * `constructed` ≠ `active_on_live_path`.
 */
enum class FeatureLifecycle {
    /** Объект создан в composition root. */
    CONSTRUCTED,

    /** Готов к использованию (зависимости/конфиг OK). */
    AVAILABLE,

    /** Включён настройкой / flag. */
    ENABLED,

    /** Реально участвует в user request path. */
    ACTIVE_ON_LIVE_PATH,

    /** Явно выключен или не подключён. */
    DISABLED,
}

data class FeatureStatus(
    val id: String,
    val lifecycle: FeatureLifecycle,
    val note: String = "",
) {
    fun short(): String = "$id=${lifecycle.name.lowercase()}${if (note.isNotBlank()) "($note)" else ""}"
}

/**
 * Снимок статусов подсистем для diagnostics (V2-01).
 */
data class FeatureStatusSnapshot(
    val capturedAtMillis: Long,
    val features: List<FeatureStatus>,
) {
    fun lines(): List<String> = features.map { it.short() }

    fun find(id: String): FeatureStatus? = features.find { it.id == id }
}
