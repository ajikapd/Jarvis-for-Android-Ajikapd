package com.jarvis.core.autonomy

/**
 * Stage 8E: маппинг capability / tool / pattern → [ProposedAction].
 * Не исполняет. Не создаёт ToolCall.
 */
object ProposedActionMapper {

    fun fromToolId(
        toolId: String,
        actionId: String = toolId,
        args: Map<String, String> = emptyMap(),
        displaySummary: String? = null,
    ): ProposedAction? {
        val kind = KIND_BY_TOOL[toolId] ?: return null
        val summary = displaySummary
            ?: SUMMARY_BY_TOOL[toolId]
            ?: toolId
        return ProposedAction(
            actionId = actionId,
            kind = kind,
            displaySummary = summary,
            toolId = toolId,
            arguments = args,
        )
    }

    fun fromCapabilityId(
        capabilityId: String,
        args: Map<String, String> = emptyMap(),
    ): ProposedAction? {
        val toolId = TOOL_BY_CAPABILITY[capabilityId] ?: return null
        return fromToolId(
            toolId = toolId,
            actionId = capabilityId.lowercase(),
            args = args,
            displaySummary = SUMMARY_BY_TOOL[toolId],
        )
    }

    /**
     * Эвристика по behavior patternKey (например `seq:HEADPHONES→music`).
     * Возвращает null если нет безопасного/известного маппинга.
     */
    fun fromBehaviorPatternKey(patternKey: String): ProposedAction? {
        val key = patternKey.lowercase()
        return when {
            "flashlight" in key || "фонарик" in key || "torch" in key ->
                fromToolId("flashlight", args = mapOf("action" to "on"))
            "volume" in key || "громк" in key ->
                fromToolId("volume", actionId = "volume_up", args = mapOf("action" to "up"))
            "music" in key || "media" in key || "headphones" in key ->
                fromToolId("music", actionId = "music_play", args = mapOf("action" to "play"))
            "brightness" in key || "ярк" in key ->
                fromToolId("brightness", args = mapOf("action" to "up"))
            else -> null
        }
    }

    /** Первые кандидаты на autonomous exec (Stage 8F) — только LOW device-safe. */
    val FIRST_SAFE_TOOL_IDS: Set<String> = setOf("flashlight", "volume", "brightness")

    private val TOOL_BY_CAPABILITY = mapOf(
        "DEVICE_LIGHT" to "flashlight",
        "DEVICE_AUDIO" to "volume",
        "DEVICE_DISPLAY" to "brightness",
        "MEDIA_PLAYBACK" to "music",
        "APP_LAUNCH" to "launcher",
        "SYSTEM_CONFIGURATION" to "system.setting",
        "COMMUNICATION_CALL" to "phone.call",
        "COMMUNICATION_SMS" to "sms",
        "KNOWLEDGE_LOOKUP" to "knowledge",
    )

    private val KIND_BY_TOOL = mapOf(
        "flashlight" to ActionKind.LOCAL_DEVICE_SAFE,
        "volume" to ActionKind.LOCAL_DEVICE_SAFE,
        "brightness" to ActionKind.LOCAL_DEVICE_SAFE,
        "timer" to ActionKind.LOCAL_DEVICE_SAFE,
        "music" to ActionKind.MEDIA_CONTROL,
        "launcher" to ActionKind.LAUNCH_APP,
        "system.setting" to ActionKind.SYSTEM_SETTINGS,
        "phone.call" to ActionKind.COMMUNICATION,
        "sms" to ActionKind.COMMUNICATION,
        "telegram.send" to ActionKind.COMMUNICATION,
        "knowledge" to ActionKind.READ_ONLY,
        "weather" to ActionKind.READ_ONLY,
        "clipboard" to ActionKind.READ_ONLY,
        "notifications.list" to ActionKind.READ_ONLY,
        "calendar" to ActionKind.READ_ONLY,
        "contacts.search" to ActionKind.READ_ONLY,
        "files.search" to ActionKind.READ_ONLY,
        "screenshot" to ActionKind.UI_AUTOMATION,
        "ui.click" to ActionKind.UI_AUTOMATION,
        "ui.type" to ActionKind.UI_AUTOMATION,
        "ui.swipe" to ActionKind.UI_AUTOMATION,
        "ui.global" to ActionKind.UI_AUTOMATION,
        "ui.read" to ActionKind.UI_AUTOMATION,
        "ui.wait" to ActionKind.UI_AUTOMATION,
    )

    private val SUMMARY_BY_TOOL = mapOf(
        "flashlight" to "Фонарик",
        "volume" to "Громкость",
        "brightness" to "Яркость",
        "timer" to "Таймер",
        "music" to "Музыка",
        "launcher" to "Приложение",
        "system.setting" to "Настройка",
        "phone.call" to "Звонок",
        "sms" to "SMS",
        "telegram.send" to "Telegram",
        "knowledge" to "Знания",
        "weather" to "Погода",
        "clipboard" to "Буфер",
        "notifications.list" to "Уведомления",
        "calendar" to "Календарь",
        "contacts.search" to "Контакты",
        "files.search" to "Файлы",
        "screenshot" to "Скриншот",
        "ui.click" to "UI click",
        "ui.type" to "UI type",
        "ui.swipe" to "UI swipe",
        "ui.global" to "UI global",
        "ui.read" to "UI read",
        "ui.wait" to "UI wait",
    )
}
