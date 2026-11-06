package com.jarvis.brain

import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.notes.CustomRoutines

/** Создать, удалить или перечислить сценарии, заданные голосом. */
class CustomRoutineTool(private val routines: CustomRoutines) : Tool {
    override val definition = ToolDefinition(
        id = "routine.custom", name = "Сценарии", description = "Создать, удалить или перечислить свои сценарии",
        parameters = listOf(ToolParameter("phrase", "просьба словами пользователя", ParameterType.STRING, required = true)),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val request = CustomRoutineParser.parse(call.arguments["phrase"]?.toString().orEmpty())
        val reply = when (request) {
            is CustomRoutineParser.Request.Define -> {
                routines.put(request.name, request.steps)
                "Запомнил сценарий «${request.name}», сэр. Шагов: ${request.steps.size}."
            }
            is CustomRoutineParser.Request.Delete ->
                if (routines.remove(request.name)) "Сценарий удалён, сэр." else "Такого сценария нет, сэр."
            CustomRoutineParser.Request.Names -> routines.names().let { names ->
                if (names.isEmpty()) "Своих сценариев пока нет, сэр." else "Ваши сценарии: ${names.joinToString(", ")}."
            }
            null -> return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что должен делать сценарий, сэр?")
        }
        return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, reply)
    }
}
