package com.jarvis.brain

import com.jarvis.core.common.model.AssistantResponseStatus
import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus

/**
 * Планирует человеческий ответ по результату инструмента.
 * Для простых команд — короткая констатация факта (разделы 27, 37 UI/UX-спеки).
 */
object ResponsePlanner {

    fun plan(result: ToolResult): String = when (result.status) {
        ToolResultStatus.SUCCESS -> result.message ?: "Готово."
        ToolResultStatus.FAILED -> result.message ?: "Не получилось выполнить действие."
        ToolResultStatus.DENIED -> result.message ?: "Политика запрещает это действие."
        ToolResultStatus.NEEDS_CONFIRMATION -> result.message ?: "Требуется подтверждение."
        ToolResultStatus.UNAVAILABLE -> result.message ?: "Функция недоступна на этом устройстве."
        ToolResultStatus.OFFLINE -> result.message ?: "Для этого нужен интернет, сейчас он недоступен."
        ToolResultStatus.AMBIGUOUS -> result.message ?: "Уточните, сэр."
    }

    /** Отображение статуса инструмента в статус ответа ассистента. */
    fun assistantStatusOf(result: ToolResult): AssistantResponseStatus =
        when (result.status) {
            ToolResultStatus.SUCCESS -> AssistantResponseStatus.PROCESSED
            ToolResultStatus.NEEDS_CONFIRMATION -> AssistantResponseStatus.NEEDS_CONFIRMATION
            ToolResultStatus.DENIED -> AssistantResponseStatus.DENIED
            else -> AssistantResponseStatus.FAILED
        }
}

/** Превращает понятый интент в вызов инструмента. */
object ToolCallFactory {

    fun build(intent: Intent, requestId: String): ToolCall? {
        return when (intent.type) {
            IntentType.FLASHLIGHT -> {
                val action = intent.slots["action"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "flashlight", arguments = mapOf("action" to action))
            }

            IntentType.VOLUME -> {
                val direction = intent.slots["direction"]?.value as? String ?: return null
                val percent = intent.slots["percent"]?.value as? Int
                ToolCall(
                    requestId = requestId,
                    toolId = "volume",
                    arguments = buildMap {
                        put("direction", direction)
                        if (percent != null) put("percent", percent)
                    }
                )
            }

            IntentType.BRIGHTNESS -> {
                val direction = intent.slots["direction"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "brightness", arguments = slotsToArgs(intent))
            }

            IntentType.TIMER -> {
                if (intent.slots.containsKey("action")) {
                    return ToolCall(requestId = requestId, toolId = "timer", arguments = slotsToArgs(intent))
                }
                val minutes = intent.slots["minutes"]?.value as? Int ?: 0
                val seconds = intent.slots["seconds"]?.value as? Int ?: 0
                if (minutes == 0 && seconds == 0) null
                else ToolCall(
                    requestId = requestId,
                    toolId = "timer",
                    arguments = mapOf("minutes" to minutes, "seconds" to seconds)
                )
            }

            IntentType.CREATE_EVENT -> {
                val action = intent.slots["action"]?.value as? String
                if (action == "list") {
                    ToolCall(requestId = requestId, toolId = "calendar", arguments = slotsToArgs(intent))
                } else {
                    val title = intent.slots["title"]?.value as? String ?: return null
                    ToolCall(
                        requestId = requestId,
                        toolId = "calendar",
                        arguments = mapOf("action" to "create", "title" to title),
                    )
                }
            }

            IntentType.SEND_SMS -> {
                val action = intent.slots["action"]?.value as? String
                if (action == "read") {
                    ToolCall(requestId = requestId, toolId = "sms", arguments = mapOf("action" to "read"))
                } else {
                    val text = intent.slots["text"]?.value as? String ?: return null
                    val recipient = intent.slots["recipient"]?.value as? String
                    ToolCall(
                        requestId = requestId,
                        toolId = "sms",
                        arguments = buildMap {
                            put("action", "send")
                            put("text", text)
                            if (recipient != null) put("recipient", recipient)
                        },
                    )
                }
            }

            IntentType.SEND_TELEGRAM -> {
                val chat = intent.slots["chat"]?.value as? String
                val text = intent.slots["text"]?.value as? String
                ToolCall(
                    requestId = requestId,
                    toolId = "telegram.send",
                    arguments = buildMap {
                        if (!chat.isNullOrBlank()) put("chat", chat)
                        if (!text.isNullOrBlank()) put("text", text)
                    },
                )
            }

            IntentType.LAUNCH_APP -> {
                val args = slotsToArgs(intent)
                if (!args.containsKey("app") && !args.containsKey("action")) return null
                ToolCall(requestId = requestId, toolId = "launcher", arguments = args)
            }

            IntentType.SYSTEM_SETTING -> {
                val name = intent.slots["name"]?.value as? String ?: return null
                val value = intent.slots["value"]?.value
                ToolCall(
                    requestId = requestId,
                    toolId = "system.setting",
                    arguments = buildMap {
                        put("name", name)
                        if (value != null) put("value", value)
                    },
                )
            }

            IntentType.CALL -> {
                val action = intent.slots["action"]?.value as? String ?: "call"
                if (action == "history" || action == "redial") {
                    ToolCall(
                        requestId = requestId,
                        toolId = "phone.call",
                        arguments = mapOf("action" to action),
                    )
                } else {
                    val contact = intent.slots["contact"]?.value as? String ?: return null
                    ToolCall(
                        requestId = requestId,
                        toolId = "phone.call",
                        arguments = mapOf("action" to "call", "contact" to contact),
                    )
                }
            }

            IntentType.CONTACTS -> {
                val query = intent.slots["query"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "contacts.search", arguments = mapOf("query" to query))
            }

            IntentType.FILES -> {
                val query = intent.slots["query"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "files.search", arguments = slotsToArgs(intent))
            }

            IntentType.UI_CLICK -> {
                val query = intent.slots["query"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "ui.click", arguments = slotsToArgs(intent))
            }

            IntentType.UI_TYPE -> {
                val text = intent.slots["text"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "ui.type", arguments = slotsToArgs(intent))
            }

            IntentType.UI_SWIPE -> {
                ToolCall(requestId = requestId, toolId = "ui.swipe", arguments = slotsToArgs(intent))
            }

            IntentType.UI_GLOBAL -> {
                val action = intent.slots["action"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "ui.global", arguments = mapOf("action" to action))
            }

            IntentType.UI_READ ->
                ToolCall(requestId = requestId, toolId = "ui.read", arguments = emptyMap())

            IntentType.UI_WAIT ->
                ToolCall(requestId = requestId, toolId = "ui.wait", arguments = slotsToArgs(intent))

            IntentType.NOTIFICATIONS ->
                ToolCall(requestId = requestId, toolId = "notifications.list", arguments = slotsToArgs(intent))

            IntentType.CLIPBOARD -> {
                val action = intent.slots["action"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "clipboard", arguments = slotsToArgs(intent))
            }

            IntentType.SCREENSHOT ->
                ToolCall(requestId = requestId, toolId = "screenshot", arguments = emptyMap())

            IntentType.WEATHER -> {
                val city = intent.slots["city"]?.value as? String
                val day = (intent.slots["day"]?.value as? Number)?.toInt()
                    ?: (intent.slots["day"]?.value as? String)?.toIntOrNull()
                ToolCall(
                    requestId = requestId,
                    toolId = "weather",
                    arguments = buildMap {
                        if (city != null) put("city", city)
                        if (day != null) put("day", day)
                    },
                )
            }

            IntentType.NEWS -> ToolCall(requestId = requestId, toolId = "news", arguments = emptyMap())
            IntentType.RATES -> ToolCall(requestId = requestId, toolId = "rates", arguments = emptyMap())
            IntentType.LOCATION -> ToolCall(requestId = requestId, toolId = "location", arguments = emptyMap())

            IntentType.PHONE_CONTROL ->
                ToolCall(requestId = requestId, toolId = "phone.control", arguments = slotsToArgs(intent))

            IntentType.FORGET ->
                ToolCall(requestId = requestId, toolId = "memory.forget", arguments = slotsToArgs(intent))

            IntentType.BRIEFING ->
                ToolCall(requestId = requestId, toolId = "briefing", arguments = emptyMap())

            IntentType.NAVIGATE ->
                ToolCall(requestId = requestId, toolId = "navigate", arguments = slotsToArgs(intent))

            IntentType.WEB_SEARCH ->
                ToolCall(requestId = requestId, toolId = "web.search", arguments = slotsToArgs(intent))

            IntentType.CAMERA ->
                ToolCall(requestId = requestId, toolId = "camera", arguments = slotsToArgs(intent))

            IntentType.REMINDER ->
                ToolCall(requestId = requestId, toolId = "reminder", arguments = slotsToArgs(intent))

            IntentType.SYSTEM_STATUS ->
                ToolCall(requestId = requestId, toolId = "system.status", arguments = emptyMap())

            IntentType.MUSIC ->
                ToolCall(requestId = requestId, toolId = "music", arguments = slotsToArgs(intent))

            IntentType.KNOWLEDGE -> {
                val query = intent.slots["query"]?.value as? String ?: return null
                ToolCall(requestId = requestId, toolId = "knowledge", arguments = mapOf("query" to query))
            }

            IntentType.UNKNOWN, IntentType.CHAT -> null
        }
    }

    private fun slotsToArgs(intent: Intent): Map<String, Any> =
        intent.slots.mapValues { it.value.value }
}