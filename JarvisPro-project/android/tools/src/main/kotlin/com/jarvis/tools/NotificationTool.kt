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
    private companion object {
        const val OWN_PACKAGE = "com.jarvis.assistant"
        const val SPOKEN_ITEMS = 5
    }


    override val definition = ToolDefinition(
        id = "notifications.list",
        name = "Уведомления",
        description = "Читает уведомления, закрывает, жмёт действие или отвечает текстом (RemoteInput)",
        parameters = listOf(
            ToolParameter(
                "action",
                "list, clear или dismiss",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("list", "clear", "dismiss"),
            ),
            ToolParameter("key", "Ключ уведомления", ParameterType.STRING, false),
            ToolParameter("query", "Фильтр по заголовку/тексту", ParameterType.STRING, false),
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
                    .filter { it.packageName != OWN_PACKAGE && it.packageName != "com.jarvis.pro" && it.title.isNotBlank() && it.text.isNotBlank() }
                if (q.isNotBlank()) {
                    items = items.filter {
                        it.title.lowercase().contains(q) || it.text.lowercase().contains(q) ||
                            it.packageName.lowercase().contains(q)
                    }
                }
                if (items.isEmpty()) {
                    ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Нет активных уведомлений.")
                } else {
                    val summary = items.take(SPOKEN_ITEMS).joinToString("; ") { "${it.title}: ${it.text.take(60)}" }
                    ToolResult(
                        call.requestId,
                        definition.id,
                        ToolResultStatus.SUCCESS,
                        "Уведомлений: ${items.size}, сэр. $summary.",
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
            else -> ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Неизвестное действие")
        }
    }
}

/** Ответ в мессенджер из уведомления. Уходит живому человеку, поэтому только с подтверждением. */
class NotificationReplyTool : Tool {
    override val definition = ToolDefinition(
        id = ID,
        name = "Ответ на сообщение",
        description = "Ответить на объявленное сообщение из уведомления",
        parameters = listOf(
            ToolParameter("text", "Текст ответа", ParameterType.STRING, true),
            ToolParameter("query", "От кого сообщение", ParameterType.STRING, false),
        ),
        riskLevel = RiskLevel.CONFIRM,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val text = call.arguments["text"]?.toString()?.trim().orEmpty()
        if (text.isBlank()) return result(call, ToolResultStatus.AMBIGUOUS, "Что ответить, сэр?")
        val key = resolveKey(call) ?: return result(call, ToolResultStatus.AMBIGUOUS, "Сообщения для ответа нет, сэр.")
        return if (JarvisNotificationListenerService.instance?.replyText(key, text) == true) {
            result(call, ToolResultStatus.SUCCESS, "Ответил, сэр.")
        } else {
            result(call, ToolResultStatus.FAILED, "На это сообщение нельзя ответить отсюда, сэр.")
        }
    }

    private fun result(call: ToolCall, status: ToolResultStatus, message: String) =
        ToolResult(call.requestId, definition.id, status, message)

    private fun resolveKey(call: ToolCall): String? {
        val q = call.arguments["query"]?.toString()?.lowercase().orEmpty()
        if (q.isBlank()) return JarvisNotificationListenerService.lastAnnouncedKey
        return JarvisNotificationListenerService.snapshot().firstOrNull {
            it.title.lowercase().contains(q) || it.text.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }?.key
    }

    companion object {
        const val ID = "notifications.reply"
    }
}
