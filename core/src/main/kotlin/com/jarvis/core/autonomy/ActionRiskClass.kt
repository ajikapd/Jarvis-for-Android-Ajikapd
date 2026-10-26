package com.jarvis.core.autonomy

/**
 * Stage 8B: **severity риска** действия (насколько опасно).
 *
 * Не смешивать с разрешением (ALLOW/DENY) — это Stage 8C/8D.
 * Не смешивать с [com.jarvis.core.common.model.RiskLevel] ToolDefinition.
 *
 * ```
 * FLASHLIGHT → LOW     ≠ ALLOW
 * DELETE_DATA → HIGH   ≠ DENY (автоматически)
 * ```
 *
 * Unknown actions → [CRITICAL] (наиболее консервативный класс; отдельного UNKNOWN нет).
 */
enum class ActionRiskClass {
    /** Нет изменения состояния / побочного эффекта. */
    NONE,

    /** Небольшой локальный обратимый эффект. */
    LOW,

    /** Заметный пользовательский/системный эффект, обычно обратимый. */
    MEDIUM,

    /** Существенный внешний эффект, мутация данных или потенциальный ущерб. */
    HIGH,

    /** Финансовые / критически тяжёлые последствия; также fallback для unknown. */
    CRITICAL,
}
