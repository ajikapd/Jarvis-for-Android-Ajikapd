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

/** Инструмент, который передаёт дело приложению телефона через Intent. */
private abstract class IntentTool(private val context: Context) : Tool {
    protected abstract fun intentFor(call: ToolCall): Intent?
    protected abstract val done: String
    protected abstract val missing: String

    override fun execute(call: ToolCall): ToolResult {
        val intent = intentFor(call)
            ?: return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, missing)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, done)
        } catch (t: Throwable) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Нет подходящего приложения, сэр.")
        }
    }
}

private fun textParameter(name: String, description: String) =
    ToolParameter(name, description, ParameterType.STRING, required = true)

/** Маршрут или место на карте в приложении карт по умолчанию. */
fun navigationTool(context: Context): Tool = object : IntentTool(context) {
    override val definition = ToolDefinition(
        id = "navigate", name = "Навигация", description = "Проложить маршрут или показать место на карте",
        parameters = listOf(textParameter("destination", "куда")),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )
    override val done = "Открываю маршрут, сэр."
    override val missing = "Куда прокладывать маршрут, сэр?"

    override fun intentFor(call: ToolCall): Intent? {
        val place = call.arguments["destination"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
    }
}

/** Поиск в браузере, когда нужна страница, а не ответ голосом. */
fun webSearchTool(context: Context): Tool = object : IntentTool(context) {
    override val definition = ToolDefinition(
        id = "web.search", name = "Поиск в сети", description = "Открыть поиск в браузере",
        parameters = listOf(textParameter("query", "что искать")),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )
    override val done = "Открываю результаты, сэр."
    override val missing = "Что искать, сэр?"

    override fun intentFor(call: ToolCall): Intent? {
        val query = call.arguments["query"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/search/?text=" + Uri.encode(query)))
    }
}

/** Открывает камеру готовой к съёмке; снимок делает пользователь. */
fun cameraTool(context: Context): Tool = object : IntentTool(context) {
    override val definition = ToolDefinition(
        id = "camera", name = "Камера", description = "Открыть камеру для фото или видео",
        parameters = listOf(
            ToolParameter("mode", "photo или video", ParameterType.ENUM, required = false, allowedValues = listOf("photo", "video")),
        ),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )
    override val done = "Камера готова, сэр."
    override val missing = ""

    override fun intentFor(call: ToolCall): Intent = Intent(
        if (call.arguments["mode"]?.toString() == "video") {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        },
    )
}
