package com.jarvis.core.autonomy

/**
 * Stage 8A: итоговый вердикт автономности.
 *
 * ALLOW **не** следует из одного только high confidence —
 * нужны risk / source / autonomy level / policy / safety gate.
 */
enum class AutonomyVerdictType {
    /** Нет действия: молчать или только UI decision без execution. */
    NO_ACTION,

    /** Действие возможно только после явного подтверждения пользователя. */
    REQUIRE_CONFIRMATION,

    /** Разрешено к исполнению через ToolExecutor (появятся не раньше Stage 8E/8F). */
    ALLOW,

    /** Запрещено политикой / safety / пользователем. */
    DENY,
}
