package com.jarvis.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
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
 * Календарь: создание события и список ближайших.
 */
class CalendarTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "calendar",
        name = "Календарь",
        description = "Создаёт событие или читает ближайшие события календаря",
        parameters = listOf(
            ToolParameter(
                name = "action",
                description = "create или list",
                type = ParameterType.ENUM,
                required = false,
                allowedValues = listOf("create", "list"),
            ),
            ToolParameter(
                name = "title",
                description = "Название события (для create)",
                type = ParameterType.STRING,
                required = false,
            ),
            ToolParameter(
                name = "limit",
                description = "Сколько событий показать (list)",
                type = ParameterType.INTEGER,
                required = false,
            ),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val action = call.arguments["action"]?.toString()?.lowercase()
            ?: if (call.arguments["title"] != null) "create" else "list"
        return when (action) {
            "list" -> listEvents(call)
            else -> createEvent(call)
        }
    }

    private fun listEvents(call: ToolCall): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.DENIED,
                "Нет разрешения на чтение календаря.",
            )
        }
        val limit = (call.arguments["limit"] as? Number)?.toInt()
            ?: call.arguments["limit"]?.toString()?.toIntOrNull()
            ?: 5
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return try {
            val rows = ArrayList<String>()
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                ),
                "${CalendarContract.Events.DTSTART} >= ?",
                arrayOf(now.toString()),
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { c ->
                val iTitle = c.getColumnIndex(CalendarContract.Events.TITLE)
                val iStart = c.getColumnIndex(CalendarContract.Events.DTSTART)
                var n = 0
                while (c.moveToNext() && n < limit.coerceIn(1, 20)) {
                    val title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "без названия"
                    val start = fmt.format(Date(c.getLong(iStart)))
                    rows += "$title ($start)"
                    n++
                }
            }
            if (rows.isEmpty()) {
                ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Ближайших событий нет.")
            } else {
                ToolResult(
                    call.requestId, definition.id, ToolResultStatus.SUCCESS,
                    "Ближайшие события: ${rows.joinToString("; ")}.",
                )
            }
        } catch (t: Throwable) {
            ToolResult(
                call.requestId, definition.id, ToolResultStatus.FAILED,
                "Не удалось прочитать календарь: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun createEvent(call: ToolCall): ToolResult {
        try {
            val title = call.arguments["title"] as? String
            if (title.isNullOrBlank()) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.AMBIGUOUS,
                    message = "Не указано название события",
                )
            }
            if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.DENIED,
                    message = "Нет разрешения на запись в календарь. Выдай разрешение в настройках.",
                )
            }

            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, firstCalendarId() ?: -1L)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, now + 24 * 60 * 60 * 1000L)
                put(CalendarContract.Events.DTEND, now + 24 * 60 * 60 * 1000L + 60 * 60 * 1000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                return ToolResult(
                    requestId = call.requestId,
                    toolId = definition.id,
                    status = ToolResultStatus.FAILED,
                    message = "Не удалось создать событие в календаре",
                )
            }

            return ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.SUCCESS,
                message = "Событие «$title» добавлено в календарь на завтра.",
                data = mapOf("eventId" to uri.lastPathSegment.orEmpty(), "title" to title),
            )
        } catch (t: Throwable) {
            return ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.FAILED,
                message = "Не удалось создать событие: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun firstCalendarId(): Long? = runCatching {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }.getOrNull()
}
