package com.jarvis.core.common.model

/**
 * Понятое намерение пользователя.
 * type + slots — результат детерминированного IntentResolver-а (или позже LLM),
 * confidence — насколько уверенно это распознано.
 */
data class Intent(
    val type: IntentType,
    val normalizedText: String,
    val slots: Map<String, Slot> = emptyMap(),
    val confidence: Float = 0f,
    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.LOW
) {
    companion object {
        fun unknown(normalizedText: String) = Intent(
            type = IntentType.UNKNOWN,
            normalizedText = normalizedText
        )
    }
}

enum class IntentType {
    /** Управление фонариком. */
    FLASHLIGHT,

    /** Громкость (мультимедиа/звонок). */
    VOLUME,

    /** Яркость экрана. */
    BRIGHTNESS,

    /** Таймер/секундомер. */
    TIMER,

    /** Создание события календаря. */
    CREATE_EVENT,

    /** Отправка SMS. */
    SEND_SMS,

    /** Написать сообщение в Telegram (поиск чата → ввод → отправка). */
    SEND_TELEGRAM,

    /** Выполнить/открыть приложение. */
    LAUNCH_APP,

    /** Изменение системной настройки (режим полёта, wifi и т.д.). */
    SYSTEM_SETTING,

    /** Звонок. */
    CALL,

    /** Поиск контакта. */
    CONTACTS,

    /** Поиск/открытие файла. */
    FILES,

    /** UI: клик. */
    UI_CLICK,

    /** UI: ввод текста. */
    UI_TYPE,

    /** UI: свайп. */
    UI_SWIPE,

    /** UI: системная кнопка. */
    UI_GLOBAL,

    /** UI: прочитать/описать экран. */
    UI_READ,

    /** UI: ждать появления узла (текст/id) с таймаутом. */
    UI_WAIT,

    /** Уведомления. */
    NOTIFICATIONS,

    /** Буфер обмена. */
    CLIPBOARD,

    /** Скриншот. */
    SCREENSHOT,

    /** Погода. */
    WEATHER,

    /** Состояние телефона: заряд, сеть, память. */
    SYSTEM_STATUS,

    /** Напоминание с текстом и временем. */
    REMINDER,

    /** Музыка / медиа. */
    MUSIC,

    /** Факты / знания (Wikipedia и т.п.). */
    KNOWLEDGE,

    /** Обычный разговор/вопрос без привязки к инструменту. */
    CHAT,

    /** Намерение не распознано. */
    UNKNOWN
}

enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/** Именованный слот интента: "action" → "on" / "off" и т.п. */
data class Slot(
    val name: String,
    val value: Any,
    val confidence: Float = 1f
)