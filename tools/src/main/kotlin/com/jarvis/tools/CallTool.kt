package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "phone.call",
        name = "Звонок",
        description = "Позвонить контакту/номеру или прочитать историю звонков",
        parameters = listOf(
            ToolParameter("contact", "Имя контакта или номер", ParameterType.STRING, required = false),
            ToolParameter(
                "action",
                "call или history",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("call", "history", "redial"),
            ),
            ToolParameter("limit", "Сколько записей истории (по умолчанию 5)", ParameterType.INTEGER, false),
        ),
        riskLevel = RiskLevel.CONFIRM,
        requiredPermissions = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
        ),
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val action = call.arguments["action"]?.toString()?.lowercase()
            ?: if (call.arguments["contact"] == null && call.arguments["number"] == null) "history" else "call"
        return when (action) {
            "history" -> history(call)
            "redial" -> redial(call)
            else -> dial(call)
        }
    }

    /** Перезвонить тому, с кем говорили последним. */
    private fun redial(call: ToolCall): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.DENIED, "Нет разрешения на историю звонков.")
        }
        val number = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER), null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (number.isNullOrBlank()) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "В истории нет звонков, сэр.")
        }
        return dial(call.copy(arguments = mapOf("number" to number)))
    }

    private fun history(call: ToolCall): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.DENIED,
                "Нет разрешения на историю звонков.",
            )
        }
        val limit = (call.arguments["limit"] as? Number)?.toInt()
            ?: call.arguments["limit"]?.toString()?.toIntOrNull()
            ?: 5
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            )
            val rows = ArrayList<String>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val iNum = c.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iType = c.getColumnIndex(CallLog.Calls.TYPE)
                val iDate = c.getColumnIndex(CallLog.Calls.DATE)
                var n = 0
                while (c.moveToNext() && n < limit.coerceIn(1, 20)) {
                    val name = c.getString(iName)?.takeIf { it.isNotBlank() }
                        ?: c.getString(iNum).orEmpty()
                    val type = when (c.getInt(iType)) {
                        CallLog.Calls.INCOMING_TYPE -> "вх"
                        CallLog.Calls.OUTGOING_TYPE -> "исх"
                        CallLog.Calls.MISSED_TYPE -> "проп"
                        else -> "?"
                    }
                    val whenStr = fmt.format(Date(c.getLong(iDate)))
                    rows += "$type $name ($whenStr)"
                    n++
                }
            }
            if (rows.isEmpty()) {
                ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "История звонков пуста.")
            } else {
                ToolResult(
                    call.requestId, definition.id, ToolResultStatus.SUCCESS,
                    "Последние звонки: ${rows.joinToString("; ")}.",
                )
            }
        } catch (t: Throwable) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private fun dial(call: ToolCall): ToolResult {
        val raw = call.arguments["contact"]?.toString()?.trim().orEmpty()
        if (raw.isBlank() && call.arguments["number"] == null) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Кому звонить?")
        }
        val presetNumber = call.arguments["number"]?.toString()?.filter { it.isDigit() || it == '+' }
        val match = if (presetNumber.isNullOrBlank()) ContactResolver.resolvePhone(context, raw) else null
        val number = presetNumber?.takeIf { it.length >= 3 }
            ?: match?.number
            ?: raw.filter { it.isDigit() || it == '+' }
        if (number.length < 3) {
            return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.FAILED,
                "Не нашёл контакт «$raw». Нужно разрешение на контакты или точный номер.",
            )
        }
        val hasCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        return try {
            if (hasCall) {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            val label = call.arguments["contact"]?.toString()?.takeIf { it.isNotBlank() }
                ?: match?.name
                ?: number
            ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                if (hasCall) "Звоню «$label»." else "Открыл набор номера «$label».",
                mapOf("number" to number, "name" to label),
            )
        } catch (t: Throwable) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }
}
