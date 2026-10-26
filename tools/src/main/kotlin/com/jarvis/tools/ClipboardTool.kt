package com.jarvis.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus

class ClipboardTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "clipboard",
        name = "Буфер обмена",
        description = "Читает или записывает текст в буфер обмена",
        parameters = listOf(
            ToolParameter(
                "action",
                "get или set",
                ParameterType.ENUM,
                required = true,
                allowedValues = listOf("get", "set"),
            ),
            ToolParameter("text", "Текст для записи (для set)", ParameterType.STRING, false),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val action = call.arguments["action"]?.toString()?.lowercase().orEmpty()
        return when (action) {
            "get" -> {
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) {
                    ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Буфер обмена пуст.")
                } else {
                    ToolResult(
                        call.requestId,
                        definition.id,
                        ToolResultStatus.SUCCESS,
                        "В буфере: ${text.take(200)}",
                        mapOf("text" to text),
                    )
                }
            }
            "set" -> {
                val text = call.arguments["text"]?.toString().orEmpty()
                if (text.isBlank()) {
                    return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Нет текста")
                }
                cm.setPrimaryClip(ClipData.newPlainText("jarvis", text))
                ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Скопировал в буфер.")
            }
            else -> ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "action: get или set")
        }
    }
}
