package com.jarvis.app

/**
 * Каталог реальных возможностей ассистента (tools из BrainGraph).
 * Используется вкладкой «КОМАНДЫ» и гайдом первого запуска.
 */
object AssistantCapabilities {

    enum class RiskTag { SAFE, CONFIRM, SENSITIVE, ONLINE }

    data class Command(
        val id: String,
        val title: String,
        val category: String,
        val description: String,
        val examples: List<String>,
        val toolId: String?,
        val risk: RiskTag,
        val needs: String = "",
    )

    val ALL: List<Command> = listOf(
        Command(
            "flashlight", "Фонарик", "Устройство",
            "Включить или выключить фонарик камеры.",
            listOf("джарвис, включи фонарик", "джарвис, выключи фонарик"),
            "flashlight", RiskTag.SAFE,
        ),
        Command(
            "volume", "Громкость", "Устройство",
            "Сделать громче или тише.",
            listOf("джарвис, громче", "джарвис, тише", "джарвис, поставь громкость на 50"),
            "volume", RiskTag.SAFE,
        ),
        Command(
            "brightness", "Яркость", "Устройство",
            "Изменить яркость экрана (нужно разрешение «Изменение системных настроек»).",
            listOf("джарвис, ярче", "джарвис, темнее"),
            "brightness", RiskTag.SAFE, "WRITE_SETTINGS",
        ),
        Command(
            "timer", "Таймер", "Устройство",
            "Поставить таймер.",
            listOf("джарвис, поставь таймер на 5 минут", "джарвис, таймер 10 секунд"),
            "timer", RiskTag.SAFE,
        ),
        Command(
            "launcher", "Запуск приложений", "Приложения",
            "Открыть приложение по имени.",
            listOf("джарвис, открой YouTube", "джарвис, запусти Telegram", "джарвис, открой музыку"),
            "launcher", RiskTag.SAFE,
        ),
        Command(
            "music", "Музыка", "Приложения",
            "Управление воспроизведением (play / pause / next).",
            listOf("джарвис, включи музыку", "джарвис, пауза", "джарвис, следующая песня"),
            "music", RiskTag.SAFE,
        ),
        Command(
            "weather", "Погода", "Информация",
            "Погода по городу (нужен интернет).",
            listOf("джарвис, какая погода в Москве", "джарвис, погода завтра"),
            "weather", RiskTag.ONLINE,
        ),
        Command(
            "knowledge", "Знания / вопросы", "Информация",
            "Ответ на вопрос через локальную модель или knowledge-tool.",
            listOf("джарвис, что такое квантовая физика", "джарвис, расскажи анекдот"),
            "knowledge", RiskTag.SAFE, "Модель диалога",
        ),
        Command(
            "calendar", "Календарь", "Органайзер",
            "Список дел или создание события.",
            listOf("джарвис, что у меня сегодня в календаре", "джарвис, создай встречу завтра в 15:00"),
            "calendar", RiskTag.CONFIRM, "Календарь",
        ),
        Command(
            "contacts", "Контакты", "Связь",
            "Поиск контакта.",
            listOf("джарвис, найди контакт Маша"),
            "contacts.search", RiskTag.CONFIRM, "Контакты",
        ),
        Command(
            "call", "Звонки", "Связь",
            "Показать историю или набрать номер (с подтверждением).",
            listOf("джарвис, позвони маме", "джарвис, история звонков"),
            "phone.call", RiskTag.SENSITIVE, "Телефон",
        ),
        Command(
            "sms", "SMS", "Связь",
            "Прочитать входящие или отправить SMS (с подтверждением).",
            listOf("джарвис, прочитай SMS", "джарвис, напиши SMS маме: я опаздываю"),
            "sms", RiskTag.SENSITIVE, "SMS",
        ),
        Command(
            "telegram", "Telegram", "Связь",
            "Отправить сообщение в Telegram (с подтверждением).",
            listOf("джарвис, напиши в телеграм Ивану: привет"),
            "telegram.send", RiskTag.SENSITIVE, "Telegram",
        ),
        Command(
            "notifications", "Уведомления", "Система",
            "Список недавних уведомлений (нужен Notification Listener).",
            listOf("джарвис, какие уведомления", "джарвис, что пришло"),
            "notifications.list", RiskTag.CONFIRM, "Notification Listener",
        ),
        Command(
            "clipboard", "Буфер обмена", "Система",
            "Прочитать или записать буфер.",
            listOf("джарвис, что в буфере", "джарвис, скопируй текст привет"),
            "clipboard", RiskTag.SAFE,
        ),
        Command(
            "screenshot", "Скриншот", "Система",
            "Сделать снимок экрана.",
            listOf("джарвис, сделай скриншот"),
            "screenshot", RiskTag.CONFIRM,
        ),
        Command(
            "files", "Файлы", "Система",
            "Поиск файлов по имени.",
            listOf("джарвис, найди файл отчёт"),
            "files.search", RiskTag.CONFIRM,
        ),
        Command(
            "settings", "Настройки", "Система",
            "Переключить Wi‑Fi / Bluetooth / режим полёта (где доступно).",
            listOf("джарвис, включи Wi‑Fi", "джарвис, выключи Bluetooth"),
            "system.setting", RiskTag.CONFIRM, "WRITE_SETTINGS",
        ),
        Command(
            "ui", "Управление экраном", "Автоматизация",
            "Нажатия, ввод, свайпы через Accessibility (нужна служба JARVIS).",
            listOf("джарвис, нажми назад", "джарвис, прочитай экран", "джарвис, свайпни вниз"),
            "ui.global", RiskTag.SENSITIVE, "Accessibility",
        ),
        Command(
            "memory", "Память", "Память",
            "Запомнить факт о тебе (вкладка ПАМЯТЬ).",
            listOf("джарвис, запомни: мой день рождения 15 марта", "джарвис, я живу в Москве"),
            null, RiskTag.SAFE,
        ),
        Command(
            "assist", "Системный помощник", "Система",
            "Долгое нажатие кнопки Home / жест ассистента, если JARVIS выбран цифровым помощником.",
            listOf("(жест ассистента)", "долгий Home → JARVIS"),
            null, RiskTag.SAFE, "ROLE_ASSISTANT",
        ),
        Command(
            "live", "LIVE-режим", "Режимы",
            "Непрерывный диалог без wake-word (вкладка LIVE).",
            listOf("Открой LIVE → Активировать"),
            null, RiskTag.SAFE,
        ),
    )

    fun byCategory(): Map<String, List<Command>> =
        ALL.groupBy { it.category }
}
