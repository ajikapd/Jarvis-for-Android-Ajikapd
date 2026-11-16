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

/** Голосом включает и выключает то, что Джарвис говорит сам. */
class AnnounceSettingsTool(private val apply: (kind: String, on: Boolean) -> Unit) : Tool {
    override val definition = ToolDefinition(
        id = "announce.settings", name = "Объявления", description = "Объявлять звонки, сообщения и подсказки или молчать",
        parameters = listOf(
            ToolParameter("kind", "calls, messages или hints", ParameterType.ENUM, required = true, allowedValues = listOf("calls", "messages", "hints")),
            ToolParameter("on", "true или false", ParameterType.STRING, required = true),
        ),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val kind = call.arguments["kind"]?.toString()
        val label = LABELS[kind]
            ?: return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что именно, сэр?")
        val on = call.arguments["on"]?.toString() != "false"
        apply(kind.orEmpty(), on)
        val reply = if (on) "Буду сообщать $label, сэр." else "Больше не сообщаю $label, сэр."
        return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, reply)
    }

    private companion object {
        val LABELS = mapOf("calls" to "о звонках", "messages" to "о сообщениях", "hints" to "о делах")
    }
}
