package com.jarvis.core.autonomy

/**
 * Stage 8B: **категория** действия (что это за действие).
 * Не путать с [ActionRiskClass] (насколько опасно) и с
 * [com.jarvis.core.common.model.RiskLevel] (ToolDefinition).
 *
 * Бывший Stage 8A `ActionRiskClass` category-набор перенесён сюда,
 * чтобы `ActionRiskClass` мог стать severity taxonomy (NONE…CRITICAL).
 */
enum class ActionKind {
    /** Нет действия / нет side effect. */
    NONE,

    /** Только чтение / ответ без изменения устройства. */
    READ_ONLY,

    /** Фонарик, небольшой volume/brightness step. */
    LOCAL_DEVICE_SAFE,

    /** Запуск приложения. */
    LAUNCH_APP,

    /** Медиа / музыка. */
    MEDIA_CONTROL,

    /** SMS / мессенджеры / звонки. */
    COMMUNICATION,

    /** Системные настройки (заметное изменение состояния). */
    SYSTEM_SETTINGS,

    /** Accessibility / UI gestures. */
    UI_AUTOMATION,

    /** Удаление данных / очистка. */
    DATA_DELETION,

    /** Платежи / покупки / auth. */
    FINANCIAL,

    /** Неизвестная / неподдерживаемая категория — классифицируется консервативно. */
    UNKNOWN,
}
