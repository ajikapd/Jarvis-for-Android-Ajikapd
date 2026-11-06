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
            ?: if (call.arguments.containsKey("days")) WEEK_LIMIT else 5
        val now = System.currentTimeMillis()
        val day = (call.arguments["day"] as? Number)?.toInt() ?: call.arguments["day"]?.toString()?.toIntOrNull()
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = day?.let { java.time.LocalDate.now().plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        val begin = dayStart ?: now
        val days = (call.arguments["days"] as? Number)?.toInt()
        val end = dayStart?.plus(DAY_MS) ?: (now + (days ?: LIST_WINDOW_DAYS).toLong() * DAY_MS)
        // Instances, а не Events: повторяющиеся встречи иначе не попадают в список.
        val instances = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString()).appendPath(end.toString()).build()
        val fmt = SimpleDateFormat(if (day != null) "HH:mm" else "dd.MM HH:mm", Locale.getDefault())
        // Событие на весь день хранится в полночь UTC: местное время показало бы 03:00.
        val dayFormat = SimpleDateFormat("d MMMM", Locale("ru")).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return try {
            val rows = ArrayList<String>()
            context.contentResolver.query(
                instances,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY,
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val iTitle = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val iStart = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                var n = 0
                while (c.moveToNext() && n < limit.coerceIn(1, 20)) {
                    val title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "без названия"
                    val allDay = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)) == 1
                    rows += if (allDay) "$title (${dayFormat.format(Date(c.getLong(iStart)))})"
                    else "$title (${fmt.format(Date(c.getLong(iStart)))})"
                    n++
                }
            }
            if (rows.isEmpty()) {
                ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, if (day != null) "В этот день событий нет, сэр." else "Ближайших событий нет, сэр.")
            } else {
                ToolResult(
                    call.requestId, definition.id, ToolResultStatus.SUCCESS,
                    if (day != null) "События: ${rows.joinToString("; ")}." else "Ближайшие события: ${rows.joinToString("; ")}.",
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

            // В названии может быть время: «встреча завтра в три».
            val moment = java.time.LocalDateTime.now()
            val parsed = com.jarvis.core.reminder.ReminderParser.parse(title, moment)
            val eventTitle = parsed.text.ifBlank { title }
            val startAt = parsed.at ?: moment.plusDays(1)
            val start = startAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, firstCalendarId() ?: -1L)
                put(CalendarContract.Events.TITLE, eventTitle)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + HOUR_MS)
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
                message = "Записал, сэр: $eventTitle, " +
                    com.jarvis.core.reminder.ReminderSpeech.whenPhrase(startAt, moment) + ".",
                data = mapOf("eventId" to uri.lastPathSegment.orEmpty(), "title" to eventTitle),
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

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
        const val WEEK_LIMIT = 12
        const val LIST_WINDOW_DAYS = 30
    }
}
