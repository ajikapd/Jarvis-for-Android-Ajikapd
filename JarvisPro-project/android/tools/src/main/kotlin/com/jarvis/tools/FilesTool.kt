package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus

class FilesTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "files.search",
        name = "Файлы",
        description = "Ищет медиафайл по имени и открывает его",
        parameters = listOf(
            ToolParameter("query", "Часть имени файла", ParameterType.STRING, required = true),
            ToolParameter(
                "action",
                "open или share",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("open", "share"),
            ),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что искать?")
        }
        val action = call.arguments["action"]?.toString()?.lowercase() ?: "open"
        val hit = findMedia(query)
            ?: return ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Файл «$query» не найден.")
        return try {
            val intent = if (action == "share") {
                Intent(Intent.ACTION_SEND).apply {
                    type = hit.mime
                    putExtra(Intent.EXTRA_STREAM, hit.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(hit.uri, hit.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(Intent.createChooser(intent, hit.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                if (action == "share") "Открыл отправку «${hit.name}»." else "Открываю «${hit.name}».",
                mapOf("name" to hit.name, "uri" to hit.uri.toString()),
            )
        } catch (t: Throwable) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private data class Hit(val name: String, val uri: Uri, val mime: String)

    private fun findMedia(query: String): Hit? {
        val q = "%$query%"
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image/*",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/*",
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio/*",
            MediaStore.Files.getContentUri("external") to "*/*",
        )
        for ((uri, mime) in collections) {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(q),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            ) ?: continue
            cursor.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val name = it.getString(1).orEmpty()
                    val m = it.getString(2) ?: mime
                    return Hit(name, Uri.withAppendedPath(uri, id.toString()), m)
                }
            }
        }
        return null
    }
}
