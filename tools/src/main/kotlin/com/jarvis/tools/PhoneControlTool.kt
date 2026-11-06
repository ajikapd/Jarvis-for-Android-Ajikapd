package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus

/** Ответить на входящий или завершить разговор. */
class PhoneControlTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        id = "phone.control", name = "Управление звонком", description = "Ответить на звонок или сбросить его",
        parameters = listOf(
            ToolParameter("action", "answer или end", ParameterType.ENUM, required = true, allowedValues = listOf("answer", "end")),
        ),
        riskLevel = RiskLevel.LOW, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    @Suppress("DEPRECATION")
    override fun execute(call: ToolCall): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return result(call, ToolResultStatus.DENIED, "Нет разрешения управлять звонками, сэр.")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return result(call, ToolResultStatus.FAILED, "Телефония недоступна, сэр.")
        return try {
            if (call.arguments["action"]?.toString() == "answer") {
                telecom.acceptRingingCall()
                result(call, ToolResultStatus.SUCCESS, "Соединяю, сэр.")
            } else if (telecom.endCall()) {
                result(call, ToolResultStatus.SUCCESS, "Звонок завершён, сэр.")
            } else {
                result(call, ToolResultStatus.SUCCESS, "Активного звонка нет, сэр.")
            }
        } catch (e: SecurityException) {
            result(call, ToolResultStatus.DENIED, "Нет разрешения управлять звонками, сэр.")
        }
    }

    private fun result(call: ToolCall, status: ToolResultStatus, message: String) =
        ToolResult(call.requestId, definition.id, status, message)
}
