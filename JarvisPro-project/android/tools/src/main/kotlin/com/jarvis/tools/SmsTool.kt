package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
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

/**
 * SMS: отправка и чтение входящих (inbox).
 */
class SmsTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "sms",
        name = "SMS",
        description = "Отправить SMS или прочитать последние сообщения из входящих",
        parameters = listOf(
            ToolParameter(
                name = "action",
                description = "send или read",
                type = ParameterType.ENUM,
                required = false,
                allowedValues = listOf("send", "read"),
            ),
            ToolParameter(
                name = "text",
                description = "Текст сообщения (для send)",
                type = ParameterType.STRING,
                required = false,
            ),
            ToolParameter(
                name = "recipient",
                description = "Имя контакта или номер телефона",
                type = ParameterType.STRING,
                required = false,
            ),
            ToolParameter(
                name = "limit",
                description = "Сколько SMS прочитать (по умолчанию 5)",
                type = ParameterType.INTEGER,
                required = false,
            ),
        ),
        riskLevel = RiskLevel.CONFIRM,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val action = call.arguments["action"]?.toString()?.lowercase()
            ?: when {
                call.arguments["text"] != null -> "send"
                else -> "read"
            }
        return when (action) {
            "read" -> readInbox(call)
            else -> send(call)
        }
    }

    private fun readInbox(call: ToolCall): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.DENIED,
                "Нет разрешения на чтение SMS.",
            )
        }
        val limit = (call.arguments["limit"] as? Number)?.toInt()
            ?: call.arguments["limit"]?.toString()?.toIntOrNull()
            ?: 5
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return try {
            val rows = ArrayList<String>()
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                var n = 0
                while (c.moveToNext() && n < limit.coerceIn(1, 20)) {
                    val addr = c.getString(iAddr).orEmpty()
                    val body = c.getString(iBody).orEmpty().take(80)
                    val whenStr = fmt.format(Date(c.getLong(iDate)))
                    rows += "от $addr ($whenStr): $body"
                    n++
                }
            }
            if (rows.isEmpty()) {
                ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Входящих SMS нет.")
            } else {
                ToolResult(
                    call.requestId, definition.id, ToolResultStatus.SUCCESS,
                    "Последние SMS: ${rows.joinToString("; ")}.",
                )
            }
        } catch (t: Throwable) {
            ToolResult(
                call.requestId, definition.id, ToolResultStatus.FAILED,
                "Не удалось прочитать SMS: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun send(call: ToolCall): ToolResult {
        try {
            val text = call.arguments["text"] as? String
            if (text.isNullOrBlank()) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.AMBIGUOUS,
                    message = "Не указан текст сообщения",
                )
            }
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.DENIED,
                    message = "Нет разрешения на отправку SMS. Выдай разрешение в настройках.",
                )
            }

            val recipientRaw = call.arguments["recipient"] as? String
            val resolved = recipientRaw?.let { ContactResolver.resolvePhone(context, it) }
            val recipient = resolved?.number ?: recipientRaw
            if (recipient.isNullOrBlank()) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.AMBIGUOUS,
                    message = "Укажи получателя (имя контакта или номер).",
                )
            }
            val smsManager = android.telephony.SmsManager.getDefault()
            try {
                smsManager.sendTextMessage(recipient, null, text, null, null)
            } catch (t: Throwable) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.FAILED,
                    message = "Не удалось отправить SMS: ${t.message ?: t.javaClass.simpleName}",
                )
            }

            val label = resolved?.name ?: recipient
            return ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.SUCCESS,
                message = "Сообщение отправлено: $label.",
                data = mapOf("text" to text, "recipient" to recipient),
            )
        } catch (t: Throwable) {
            return ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.FAILED,
                message = "Не удалось отправить SMS: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }
}
