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
import com.jarvis.core.reminder.Reminder
import com.jarvis.core.reminder.ReminderBook
import com.jarvis.core.reminder.ReminderParser
import com.jarvis.core.reminder.ReminderSpeech
import java.time.LocalDateTime

/** Напоминания с текстом и временем: фраза разбирается здесь, срабатывание ведёт [ReminderBook]. */
class ReminderTool(
    private val book: ReminderBook,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : Tool {

    override val definition = ToolDefinition(
        id = "reminder",
        name = "Напоминания",
        description = "Поставить, показать или отменить напоминание с текстом на указанное время",
        parameters = listOf(
            ToolParameter(
                name = "action",
                description = "add, list или cancel",
                type = ParameterType.ENUM,
                required = false,
                allowedValues = listOf("add", "list", "cancel"),
            ),
            ToolParameter("phrase", "что и когда напомнить своими словами", ParameterType.STRING, required = false),
            ToolParameter("query", "какое напоминание отменить", ParameterType.STRING, required = false),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult = when (call.arguments["action"]?.toString()) {
        "list" -> list(call)
        "cancel" -> cancel(call)
        else -> add(call)
    }

    private fun add(call: ToolCall): ToolResult {
        val parsed = ReminderParser.parse(call.arguments["phrase"]?.toString().orEmpty(), now())
        val at = parsed.at ?: return result(call, ToolResultStatus.AMBIGUOUS, "Когда напомнить, сэр?")
        val saved = book.add(at, parsed.text, parsed.daily, command = call.arguments["deferred"] == true)
        return ok(call, confirmation(saved))
    }

    private fun confirmation(r: Reminder): String {
        val moment = if (r.daily) "каждый день в %d:%02d".format(r.at.hour, r.at.minute)
        else ReminderSpeech.whenPhrase(r.at, now())
        val verb = when {
            r.command && r.daily -> "Буду выполнять"
            r.command -> "Выполню"
            r.daily -> "Буду напоминать"
            else -> "Напомню"
        }
        return if (r.text.isBlank()) "$verb $moment, сэр." else "$verb $moment, сэр: ${r.text}."
    }

    private fun list(call: ToolCall): ToolResult {
        val all = book.all().sortedBy { it.at }
        if (all.isEmpty()) return ok(call, "Напоминаний нет, сэр.")
        val items = all.joinToString("; ") { r ->
            val moment = ReminderSpeech.whenPhrase(r.at, now())
            if (r.text.isBlank()) moment else "$moment: ${r.text}"
        }
        return ok(call, "Напоминаний: ${all.size}. $items.")
    }

    private fun cancel(call: ToolCall): ToolResult {
        val all = book.all()
        if (all.isEmpty()) return ok(call, "Напоминаний нет, сэр.")
        val query = call.arguments["query"]?.toString()?.trim().orEmpty().lowercase()
        val everything = call.arguments["all"]?.toString() == "true"
        val found = when {
            everything -> all
            query.isEmpty() -> all
            else -> all.filter { it.text.lowercase().contains(query) }
        }
        return when {
            found.isEmpty() -> result(call, ToolResultStatus.AMBIGUOUS, "Такого напоминания нет, сэр.")
            found.size > 1 && !everything ->
                result(call, ToolResultStatus.AMBIGUOUS, "Их ${found.size}, сэр. Какое отменить или все?")
            else -> {
                found.forEach { book.remove(it.id) }
                ok(call, if (found.size == 1) "Напоминание отменено, сэр." else "Все напоминания отменены, сэр.")
            }
        }
    }

    private fun ok(call: ToolCall, message: String) = result(call, ToolResultStatus.SUCCESS, message)

    private fun result(call: ToolCall, status: ToolResultStatus, message: String) =
        ToolResult(call.requestId, definition.id, status, message)
}
