package com.jarvis.tools

import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.DailyBriefing
import java.time.LocalDateTime

/** Сводка на день: погода, события, напоминания и предупреждения о состоянии телефона. */
class BriefingTool(
    private val findTool: (String) -> Tool?,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : Tool {

    override val definition = ToolDefinition(
        id = "briefing",
        name = "Сводка на день",
        description = "Погода, ближайшие события, напоминания и состояние телефона одним докладом",
        parameters = emptyList(),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val t = now()
        val report = DailyBriefing.compose(
            t.hour, t.minute,
            weather = section(call, "weather"),
            events = section(call, "calendar", mapOf("action" to "list", "limit" to EVENTS, "day" to 0)),
            reminders = section(call, "reminder", mapOf("action" to "list")),
            status = section(call, "system.status"),
        )
        return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, report)
    }

    /** Сбой одного раздела не должен обрушить весь доклад. */
    private fun section(call: ToolCall, toolId: String, args: Map<String, Any> = emptyMap()): String? {
        val tool = findTool(toolId) ?: return null
        val result = runCatching {
            tool.execute(ToolCall(requestId = call.requestId, toolId = toolId, arguments = args))
        }.getOrNull() ?: return null
        return result.message?.takeIf { result.status == ToolResultStatus.SUCCESS && it.isNotBlank() }
    }

    private companion object {
        const val EVENTS = 3
    }
}
