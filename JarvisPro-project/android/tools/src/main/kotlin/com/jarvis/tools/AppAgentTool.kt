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

/** «Сделай сам: …»: цикл работает в фоне, чтобы голос оставался свободен для «стоп». */
class AppAgentTool(private val start: (String) -> String) : Tool {
    override val definition = ToolDefinition(
        id = "agent.run", name = "Сделай сам", description = "Выполнить задачу в приложениях шаг за шагом",
        parameters = listOf(ToolParameter("goal", "задача", ParameterType.STRING, required = true)),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.ONLINE_ALLOWED,
    )

    override fun execute(call: ToolCall): ToolResult {
        val goal = call.arguments["goal"]?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что сделать, сэр?")
        return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, start(goal))
    }
}
