package com.jarvis.tools

import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.screencontrol.JarvisNotificationListenerService

class NotificationTool : Tool {

    override val definition = ToolDefinition(
        id = "notifications.list",
        name = "Уведомления",
        description = "Читает уведомления, закрывает, жмёт действие или отвечает текстом (RemoteInput)",
        parameters = listOf(
            ToolParameter(
                "action",
                "list, dismiss, reply_action или reply",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("list", "clear", "dismiss", "reply_action", "reply"),
            ),
            ToolParameter("key", "Ключ уведомления", ParameterType.STRING, false),
            ToolParameter("index", "Индекс действия (reply_action)", ParameterType.INTEGER, false),
            ToolParameter("query", "Фильтр по заголовку/тексту", ParameterType.STRING, false),
            ToolParameter("text", "Текст ответа (action=reply)", ParameterType.STRING, false),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        if (!JarvisNotificationListenerService.isConnected()) {
            return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.UNAVAILABLE,
                "Служба чтения уведомлений не включена. Включи её в онбординге Jarvis.",
            )
        }
        val action = call.arguments["action"]?.toString()?.lowercase() ?: "list"
        return when (action) {
            "list" -> {
                val q = call.arguments["query"]?.toString()?.lowercase().orEmpty()
                var items = JarvisNotificationListenerService.snapshot()
                if (q.isNotBlank()) {
                    items = items.filter {
                        it.title.lowercase().contains(q) || it.text.lowercase().contains(q) ||
                            it.packageName.lowercase().contains(q)
                    }
                }
                if (items.isEmpty()) {
                    ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Нет активных уведомлений.")
                } else {
                    val summary = items.take(8).joinToString("; ") {
                        val reply = if (it.actions.any { a -> a.canReply }) " [reply]" else ""
                        "${it.title.ifBlank { it.packageName }}: ${it.text.take(60)}$reply"
                    }
                    ToolResult(
                        call.requestId,
                        definition.id,
                        ToolResultStatus.SUCCESS,
                        "Уведомления (${items.size}): $summary",
                        mapOf("count" to items.size, "keys" to items.take(8).joinToString("|") { it.key }),
                    )
                }
            }
            "clear" -> {
                val ok = JarvisNotificationListenerService.instance?.clearAll() == true
                ToolResult(
                    call.requestId,
                    definition.id,
                    if (ok) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                    if (ok) "Уведомления убраны, сэр." else "Не удалось убрать уведомления.",
                )
            }
            "dismiss" -> {
                val key = call.arguments["key"]?.toString().orEmpty()
                if (key.isBlank()) {
                    return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Нужен key уведомления")
                }
                val ok = JarvisNotificationListenerService.instance?.dismiss(key) == true
                ToolResult(
                    call.requestId,
                    definition.id,
                    if (ok) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                    if (ok) "Уведомление закрыто." else "Не удалось закрыть.",
                )
            }
            "reply_action" -> {
                val key = resolveKey(call) ?: return ToolResult(
                    call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Нужен key или query",
                )
                val index = (call.arguments["index"] as? Number)?.toInt()
                    ?: call.arguments["index"]?.toString()?.toIntOrNull()
                    ?: 0
                val ok = JarvisNotificationListenerService.instance?.sendAction(key, index) == true
                ToolResult(
                    call.requestId,
                    definition.id,
                    if (ok) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                    if (ok) "Действие уведомления выполнено." else "Действие недоступно.",
                )
            }
            "reply" -> {
                val text = call.arguments["text"]?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Нужен текст ответа")
                }
                val key = resolveKey(call) ?: return ToolResult(
                    call.requestId, definition.id, ToolResultStatus.AMBIGUOUS,
                    "Укажи уведомление (key или query).",
                )
                val ok = JarvisNotificationListenerService.instance?.replyText(key, text) == true
                ToolResult(
                    call.requestId,
                    definition.id,
                    if (ok) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                    if (ok) "Ответил в уведомление." else "Это уведомление не поддерживает текстовый ответ.",
                )
            }
            else -> ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Неизвестное действие")
        }
    }

    private fun resolveKey(call: ToolCall): String? {
        val key = call.arguments["key"]?.toString().orEmpty()
        if (key.isNotBlank()) return key
        val q = call.arguments["query"]?.toString()?.lowercase().orEmpty()
        if (q.isBlank()) return null
        return JarvisNotificationListenerService.snapshot().firstOrNull {
            it.title.lowercase().contains(q) || it.text.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }?.key
    }
}
