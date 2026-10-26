package com.jarvis.core.capability

import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel

/**
 * Строит CapabilityRegistry поверх уже зарегистрированных tools.
 * Не удаляет tools — только описывает системные возможности.
 */
object ToolCapabilityCatalog {

    /**
     * Канонические capability id → метаданные + backing tool id.
     * Если tool отсутствует в [ToolRegistry], capability не регистрируется.
     */
    private data class Spec(
        val id: String,
        val title: String,
        val description: String,
        val toolId: String,
        val operations: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val examples: List<String> = emptyList(),
        val availability: CapabilityAvailability = CapabilityAvailability.ALWAYS,
        val confirmation: ConfirmationPolicy = ConfirmationPolicy.BY_RISK,
        val constraints: List<String> = emptyList(),
        val resultHints: List<String> = emptyList(),
    )

    private val SPECS: List<Spec> = listOf(
        Spec(
            id = "DEVICE_LIGHT",
            title = "Фонарик",
            description = "Управление вспышкой камеры: включить, выключить, переключить.",
            toolId = "flashlight",
            operations = listOf("on", "off", "toggle"),
            tags = listOf("flashlight", "light", "фонарик", "свет"),
            examples = listOf("включи фонарик", "выключи свет", "посвети"),
            resultHints = listOf("on|off state"),
        ),
        Spec(
            id = "DEVICE_AUDIO",
            title = "Громкость",
            description = "Изменение громкости медиа/звонка: громче, тише, процент.",
            toolId = "volume",
            operations = listOf("up", "down", "set", "mute"),
            tags = listOf("volume", "audio", "громкость", "звук"),
            examples = listOf("сделай громче", "тише", "громкость 50"),
        ),
        Spec(
            id = "DEVICE_DISPLAY",
            title = "Яркость",
            description = "Яркость экрана: ярче, темнее, уровень.",
            toolId = "brightness",
            operations = listOf("up", "down", "set"),
            tags = listOf("brightness", "яркость", "экран"),
            examples = listOf("сделай ярче", "приглуши экран"),
        ),
        Spec(
            id = "DEVICE_TIMER",
            title = "Таймер",
            description = "Одноразовый таймер / напоминание через N минут.",
            toolId = "timer",
            operations = listOf("set", "cancel"),
            tags = listOf("timer", "таймер", "будильник"),
            examples = listOf("таймер на 5 минут", "напомни через час"),
        ),
        Spec(
            id = "MEDIA_PLAYBACK",
            title = "Музыка",
            description = "Воспроизведение, пауза, следующий трек, избранное.",
            toolId = "music",
            operations = listOf("play", "pause", "resume", "next", "previous"),
            tags = listOf("music", "media", "музыка", "песня"),
            examples = listOf("включи музыку", "пауза", "следующий трек"),
        ),
        Spec(
            id = "APP_LAUNCH",
            title = "Приложения",
            description = "Открыть / свернуть / force-stop приложение по имени.",
            toolId = "launcher",
            operations = listOf("open", "minimize", "force_stop"),
            tags = listOf("launcher", "app", "приложение", "открой"),
            examples = listOf("открой телеграм", "запусти настройки"),
        ),
        Spec(
            id = "SYSTEM_CONFIGURATION",
            title = "Системные настройки",
            description = "Включить/выключить Wi‑Fi, Bluetooth и другие настройки.",
            toolId = "system.setting",
            operations = listOf("enable", "disable", "open"),
            tags = listOf("setting", "system", "wifi", "bluetooth", "настройки"),
            examples = listOf("включи wifi", "выключи bluetooth"),
            confirmation = ConfirmationPolicy.BY_RISK,
        ),
        Spec(
            id = "COMMUNICATION_CALL",
            title = "Звонок",
            description = "Позвонить контакту или по номеру.",
            toolId = "phone.call",
            operations = listOf("call", "history"),
            tags = listOf("call", "phone", "звонок", "набери"),
            examples = listOf("позвони маме", "набери этот номер"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
            confirmation = ConfirmationPolicy.ALWAYS_CONFIRM,
            constraints = listOf("requires_confirmation"),
        ),
        Spec(
            id = "COMMUNICATION_SMS",
            title = "SMS",
            description = "Отправить или прочитать SMS.",
            toolId = "sms",
            operations = listOf("send", "read"),
            tags = listOf("sms", "message", "смс", "сообщение"),
            examples = listOf("напиши смс", "прочитай последние смс"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
            confirmation = ConfirmationPolicy.ALWAYS_CONFIRM,
            constraints = listOf("requires_confirmation"),
        ),
        Spec(
            id = "COMMUNICATION_TELEGRAM",
            title = "Telegram",
            description = "Отправить сообщение в Telegram через UI automation.",
            toolId = "telegram.send",
            operations = listOf("send"),
            tags = listOf("telegram", "тг", "телеграм"),
            examples = listOf("напиши в телеграм"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
            confirmation = ConfirmationPolicy.BY_RISK,
        ),
        Spec(
            id = "CONTACTS_LOOKUP",
            title = "Контакты",
            description = "Найти контакт по имени.",
            toolId = "contacts.search",
            operations = listOf("search"),
            tags = listOf("contacts", "контакт"),
            examples = listOf("найди номер мамы"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
        ),
        Spec(
            id = "CALENDAR_EVENT",
            title = "Календарь",
            description = "Создать событие или показать ближайшие.",
            toolId = "calendar",
            operations = listOf("create", "list"),
            tags = listOf("calendar", "календарь", "событие"),
            examples = listOf("создай событие", "что в календаре"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
        ),
        Spec(
            id = "KNOWLEDGE_LOOKUP",
            title = "Знания",
            description = "Краткий факт из Википедии / поиска.",
            toolId = "knowledge",
            operations = listOf("lookup", "follow_up"),
            tags = listOf("knowledge", "wiki", "знания", "расскажи"),
            examples = listOf("кто такой Пушкин", "расскажи про дельфинов"),
            availability = CapabilityAvailability.NEEDS_ONLINE,
            resultHints = listOf("speech_text", "source", "title"),
        ),
        Spec(
            id = "WEATHER_LOOKUP",
            title = "Погода",
            description = "Текущая погода и прогноз по городу.",
            toolId = "weather",
            operations = listOf("current", "forecast"),
            tags = listOf("weather", "погода"),
            examples = listOf("какая погода", "погода в Москве"),
            availability = CapabilityAvailability.NEEDS_ONLINE,
        ),
        Spec(
            id = "FILES_SEARCH",
            title = "Файлы",
            description = "Поиск файлов на устройстве.",
            toolId = "files.search",
            operations = listOf("search"),
            tags = listOf("files", "файл"),
            examples = listOf("найди файл презентация"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
        ),
        Spec(
            id = "CLIPBOARD_ACCESS",
            title = "Буфер обмена",
            description = "Прочитать или записать буфер обмена.",
            toolId = "clipboard",
            operations = listOf("read", "write"),
            tags = listOf("clipboard", "буфер"),
            examples = listOf("что в буфере", "скопируй текст"),
        ),
        Spec(
            id = "NOTIFICATIONS_READ",
            title = "Уведомления",
            description = "Список недавних уведомлений.",
            toolId = "notifications.list",
            operations = listOf("list"),
            tags = listOf("notifications", "уведомления"),
            examples = listOf("какие уведомления"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
        ),
        Spec(
            id = "SCREEN_CAPTURE",
            title = "Скриншот",
            description = "Сделать снимок экрана.",
            toolId = "screenshot",
            operations = listOf("capture"),
            tags = listOf("screenshot", "скриншот"),
            examples = listOf("сделай скриншот"),
            availability = CapabilityAvailability.NEEDS_PERMISSION,
        ),
        Spec(
            id = "UI_CLICK",
            title = "UI · нажатие",
            description = "Нажать элемент на экране по тексту/описанию.",
            toolId = "ui.click",
            operations = listOf("click"),
            tags = listOf("ui", "click", "нажми", "screen"),
            examples = listOf("нажми отправить"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
            confirmation = ConfirmationPolicy.BY_RISK,
        ),
        Spec(
            id = "UI_TYPE",
            title = "UI · ввод",
            description = "Ввести текст в поле на экране.",
            toolId = "ui.type",
            operations = listOf("type"),
            tags = listOf("ui", "type", "введи", "screen"),
            examples = listOf("введи привет"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
        ),
        Spec(
            id = "UI_SWIPE",
            title = "UI · свайп",
            description = "Свайп по экрану.",
            toolId = "ui.swipe",
            operations = listOf("swipe"),
            tags = listOf("ui", "swipe", "свайп", "screen"),
            examples = listOf("свайпни вверх"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
        ),
        Spec(
            id = "UI_GLOBAL",
            title = "UI · системные жесты",
            description = "Назад, домой, недавние.",
            toolId = "ui.global",
            operations = listOf("back", "home", "recents"),
            tags = listOf("ui", "global", "назад", "домой", "screen"),
            examples = listOf("назад", "на главный экран"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
        ),
        Spec(
            id = "UI_READ",
            title = "UI · чтение экрана",
            description = "Прочитать текст / структуру экрана.",
            toolId = "ui.read",
            operations = listOf("read"),
            tags = listOf("ui", "read", "экран", "screen"),
            examples = listOf("что на экране"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
        ),
        Spec(
            id = "UI_WAIT",
            title = "UI · ожидание",
            description = "Подождать появления текста/пакета на экране.",
            toolId = "ui.wait",
            operations = listOf("wait"),
            tags = listOf("ui", "wait", "подожди", "screen"),
            examples = listOf("подожди пока загрузится"),
            availability = CapabilityAvailability.NEEDS_ACCESSIBILITY,
        ),
    )

    fun buildFromTools(tools: ToolRegistry): CapabilityRegistry {
        val reg = CapabilityRegistry()
        for (spec in SPECS) {
            val tool = tools.find(spec.toolId) ?: continue
            val def = tool.definition
            reg.register(
                CapabilityDescriptor(
                    id = spec.id,
                    title = spec.title,
                    description = spec.description.ifBlank { def.description },
                    operations = spec.operations,
                    argumentSchema = def.parameters,
                    resultHints = spec.resultHints,
                    permissions = def.requiredPermissions,
                    riskLevel = def.riskLevel,
                    onlineRequirement = def.onlineRequirement,
                    availability = when {
                        def.onlineRequirement == OnlineRequirement.ONLINE_REQUIRED ->
                            CapabilityAvailability.NEEDS_ONLINE
                        else -> spec.availability
                    },
                    confirmationPolicy = when {
                        def.riskLevel == RiskLevel.SENSITIVE ||
                            def.riskLevel == RiskLevel.CONFIRM ||
                            def.riskLevel == RiskLevel.BLOCKED -> ConfirmationPolicy.ALWAYS_CONFIRM
                        else -> spec.confirmation
                    },
                    backingToolId = spec.toolId,
                    tags = spec.tags,
                    examples = spec.examples,
                    constraints = spec.constraints,
                ),
            )
        }
        return reg
    }

    /** Все известные capability id (для тестов / документации). */
    fun knownCapabilityIds(): List<String> = SPECS.map { it.id }

    fun knownToolIds(): List<String> = SPECS.map { it.toolId }
}
