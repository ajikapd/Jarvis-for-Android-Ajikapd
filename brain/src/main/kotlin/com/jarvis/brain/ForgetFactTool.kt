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

/** «Забудь про кофе»: удаляет факты, где встречаются все названные слова. */
class ForgetFactTool(private val memory: () -> LongTermMemoryInterface) : Tool {
    override val definition = ToolDefinition(
        id = "memory.forget", name = "Забыть факт", description = "Удалить из памяти факт по словам",
        parameters = listOf(ToolParameter("query", "о чём забыть", ParameterType.STRING, required = true)),
        riskLevel = RiskLevel.LOW, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val stems = call.arguments["query"]?.toString().orEmpty().lowercase().split(' ')
            .filter { it.length >= MIN_WORD }.map { it.take(STEM) }
        if (stems.isEmpty()) return result(call, ToolResultStatus.AMBIGUOUS, "Что именно забыть, сэр?")
        val store = memory()
        val found = store.all().filter { fact -> stems.all { fact.text.lowercase().contains(it) } }
        if (found.isEmpty()) return result(call, ToolResultStatus.SUCCESS, "Такого я не помню, сэр.")
        found.forEach { store.remove(it.id) }
        return result(call, ToolResultStatus.SUCCESS, "Забыл, сэр.")
    }

    private fun result(call: ToolCall, status: ToolResultStatus, message: String) =
        ToolResult(call.requestId, definition.id, status, message)

    private companion object {
        const val MIN_WORD = 3
        const val STEM = 4
    }
}
