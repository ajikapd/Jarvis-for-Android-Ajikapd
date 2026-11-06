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
import com.jarvis.core.notes.PlaceBook
import com.jarvis.core.notes.Places

/** Запоминает адреса дома и работы для маршрутов. */
class PlacesTool(private val places: PlaceBook) : Tool {
    override val definition = ToolDefinition(
        id = "places", name = "Адреса", description = "Запомнить адрес дома или работы",
        parameters = listOf(ToolParameter("phrase", "просьба словами пользователя", ParameterType.STRING, required = true)),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val (key, address) = Places.saveRequest(call.arguments["phrase"]?.toString().orEmpty())
            ?: return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Какой адрес запомнить, сэр?")
        places.set(key, address)
        return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Адрес сохранён, сэр.")
    }
}
