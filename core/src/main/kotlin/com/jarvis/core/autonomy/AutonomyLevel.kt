package com.jarvis.core.autonomy

/**
 * Stage 8A: уровень автономности, задаваемый пользователем / профилем.
 * Не путать с [com.jarvis.core.common.model.RiskLevel] инструмента.
 */
enum class AutonomyLevel {
    /** Автономные действия выключены полностью. */
    OFF,

    /** Любое действие только после подтверждения. */
    CONFIRM_ALL,

    /** Разрешены только явно safe / read-only классы (когда policy Allow). */
    SAFE_ONLY,

    /** Ограниченный assist: LOW/local device при сильном контексте + policy. */
    ASSISTED,
}
