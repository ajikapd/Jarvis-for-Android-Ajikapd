package com.jarvis.core.model

/**
 * Состояние ассистента — single source of truth для UI (раздел 54 UI/UX-спеки).
 * Публикуется backend-ом через JarvisEvents/StateFlow, UI только отображает.
 */
enum class AssistantState {
    OFFLINE,     // сервис остановлен или не сконфигурирован
    STARTING,    // сервис поднимается
    IDLE,        // ядро живо, пассивно ждёт вейк-ворд/команду
    LISTENING,   // захват голоса
    THINKING,    // обработка запроса
    EXECUTING,   // выполнение инструмента
    SPEAKING,    // озвучивание ответа
    READY,       // ядро живо, но пассивно (слушает вейк-ворд)
    ERROR        // что-то сломалось, уведомление в UI
}