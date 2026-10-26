package com.jarvis.core.companion

/**
 * Источник нормализованного наблюдения телефона.
 * Android-specific типы (AccessibilityEvent и т.п.) сюда не протекают.
 */
enum class ObservationSource {
    SYSTEM_BROADCAST,
    ACCESSIBILITY,
    USAGE_STATS,
    NOTIFICATION_LISTENER,
    VOICE,
    TOOL,
    INTERNAL,
}

enum class ObservationType {
    SCREEN_ON,
    SCREEN_OFF,
    DEVICE_UNLOCKED,
    DEVICE_LOCKED,
    BATTERY_CHANGED,
    CHARGER_CONNECTED,
    CHARGER_DISCONNECTED,
    HEADPHONES_CONNECTED,
    HEADPHONES_DISCONNECTED,
    NETWORK_CHANGED,
    APP_FOREGROUND,
    APP_BACKGROUND,
    WINDOW_CHANGED,
    NOTIFICATION_POSTED,
    NOTIFICATION_REMOVED,
    USER_INTERACTION,
    VOICE_REQUEST,
    TOOL_EXECUTED,
    UNKNOWN,
}

enum class Sensitivity {
    PUBLIC,
    NORMAL,
    PRIVATE,
    SECRET,
}
