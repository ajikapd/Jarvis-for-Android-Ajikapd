package com.jarvis.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.screencontrol.ScreenController
import java.io.OutputStream

class ScreenshotTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "screenshot",
        name = "Скриншот",
        description = "Делает снимок текущего экрана и сохраняет в Галерею",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        if (!ScreenController.isAvailable()) {
            return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.UNAVAILABLE,
                "Нужна служба спец. возможностей Jarvis для скриншота.",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.UNAVAILABLE,
                "Скриншот через Accessibility доступен с Android 11+.",
            )
        }
        val bitmap = ScreenController.takeScreenshotBitmap()
            ?: return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.FAILED,
                "Не удалось сделать скриншот.",
            )
        return try {
            val name = "jarvis_${System.currentTimeMillis()}.png"
            val uri = saveToGallery(bitmap, name)
            bitmap.recycle()
            if (uri == null) {
                ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Не сохранил скриншот.")
            } else {
                ToolResult(
                    call.requestId,
                    definition.id,
                    ToolResultStatus.SUCCESS,
                    "Скриншот сохранён: $name",
                    mapOf("uri" to uri.toString(), "name" to name),
                )
            }
        } catch (t: Throwable) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private fun saveToGallery(bitmap: Bitmap, name: String): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Jarvis")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
