package com.jarvis.tools

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
import com.jarvis.screencontrol.ActionCoordinator

class UiClickTool(context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "ui.click",
        name = "Клик по экрану",
        description = "Находит элемент на текущем экране по тексту/описанию и нажимает; при необходимости прокручивает список",
        parameters = listOf(
            ToolParameter("query", "Текст или описание элемента", ParameterType.STRING, required = true),
            ToolParameter(
                "longPress",
                "Долгое нажатие",
                ParameterType.BOOLEAN,
                required = false,
            ),
            ToolParameter(
                "scroll",
                "Искать с прокруткой (true/false)",
                ParameterType.BOOLEAN,
                required = false,
            ),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString().orEmpty()
        if (query.isBlank()) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Не указан элемент")
        }
        val longPress = boolArg(call, "longPress")
        val scroll = boolArg(call, "scroll", default = true)
        val r = coordinator.findAndClick(query, longPress, scrollAttempts = if (scroll) 5 else 0)
        return ToolResult(
            call.requestId,
            definition.id,
            if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            r.message,
            buildMap {
                put("usedFallback", r.usedFallback)
                r.matchedText?.let { put("matched", it) }
            },
        )
    }

    private fun boolArg(call: ToolCall, key: String, default: Boolean = false): Boolean =
        when (val v = call.arguments[key]) {
            is Boolean -> v
            is String -> v.equals("true", true)
            null -> default
            else -> default
        }
}

class UiTypeTool(context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "ui.type",
        name = "Ввод текста",
        description = "Вводит текст в поле на экране (в фокусе или найденное по query)",
        parameters = listOf(
            ToolParameter("text", "Текст для ввода", ParameterType.STRING, required = true),
            ToolParameter("query", "Подпись поля (опционально)", ParameterType.STRING, required = false),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val text = call.arguments["text"]?.toString().orEmpty()
        val query = call.arguments["query"]?.toString()
        val r = coordinator.findAndType(query, text)
        return ToolResult(
            call.requestId,
            definition.id,
            if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            r.message,
        )
    }
}

class UiSwipeTool(context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "ui.swipe",
        name = "Свайп",
        description = "Свайп по экрану: up, down, left, right. Опционально — прокрутить до элемента until",
        parameters = listOf(
            ToolParameter(
                "direction",
                "Направление свайпа",
                ParameterType.ENUM,
                required = true,
                allowedValues = listOf("up", "down", "left", "right"),
            ),
            ToolParameter(
                "until",
                "Текст элемента, до которого прокрутить",
                ParameterType.STRING,
                required = false,
            ),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val direction = call.arguments["direction"]?.toString().orEmpty()
        val until = call.arguments["until"]?.toString().orEmpty()
        val r = if (until.isNotBlank()) {
            coordinator.scrollUntil(until, direction.ifBlank { "up" })
        } else {
            coordinator.swipe(direction)
        }
        return ToolResult(
            call.requestId,
            definition.id,
            if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            r.message,
        )
    }
}

class UiGlobalTool(context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "ui.global",
        name = "Системная кнопка",
        description = "Системные действия: back, home, recents, notifications, quick_settings, lock",
        parameters = listOf(
            ToolParameter(
                "action",
                "Действие",
                ParameterType.ENUM,
                required = true,
                allowedValues = listOf(
                    "back", "home", "recents", "notifications", "quick_settings", "lock", "power",
                ),
            ),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val action = call.arguments["action"]?.toString().orEmpty()
        val r = coordinator.global(action)
        return ToolResult(
            call.requestId,
            definition.id,
            if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            r.message,
        )
    }
}

/** «Зрение» — озвучить/вернуть, что видно на экране. */
class UiReadTool(context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "ui.read",
        name = "Прочитать экран",
        description = "Читает видимые элементы текущего экрана (тексты кнопок, поля, подписи)",
        parameters = emptyList(),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val r = coordinator.describeScreen()
        return ToolResult(
            call.requestId,
            definition.id,
            if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            r.message,
            r.matchedText?.let { mapOf("items" to it) } ?: emptyMap(),
        )
    }
}

/**
 * Ждёт появления UI-узла (текст / viewId / пакет) с backoff —
 * критично после launch на HyperOS, пока дерево Accessibility ещё пустое.
 */
class UiWaitTool(context: Context) : Tool {
    private val coordinator = ActionCoordinator(context)

    override val definition = ToolDefinition(
        id = "ui.wait",
        name = "Ждать узел",
        description = "Ждёт появления элемента на экране по тексту, viewId или пакету приложения (timeout + backoff)",
        parameters = listOf(
            ToolParameter("query", "Текст/описание элемента", ParameterType.STRING, required = false),
            ToolParameter("viewId", "Подстрока viewIdResourceName", ParameterType.STRING, required = false),
            ToolParameter(
                "package",
                "Подстрока packageName активного окна",
                ParameterType.STRING,
                required = false,
            ),
            ToolParameter(
                "timeoutMs",
                "Таймаут ожидания в мс (по умолчанию 5000)",
                ParameterType.INTEGER,
                required = false,
            ),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString().orEmpty()
        val viewId = call.arguments["viewId"]?.toString().orEmpty().ifBlank { null }
        val pkg = call.arguments["package"]?.toString().orEmpty().ifBlank { null }
        val timeout = (call.arguments["timeoutMs"] as? Number)?.toLong()
            ?: call.arguments["timeoutMs"]?.toString()?.toLongOrNull()
            ?: 5_000L
        if (query.isBlank() && viewId == null && pkg == null) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.AMBIGUOUS,
                "Укажи query, viewId или package для ожидания.",
            )
        }
        val r = coordinator.waitForNode(
            query = query,
            timeoutMs = timeout,
            packageHint = pkg,
            viewId = viewId,
        )
        return ToolResult(
            call.requestId,
            definition.id,
            if (r.success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
            r.message,
            r.matchedText?.let { mapOf("matched" to it) } ?: emptyMap(),
        )
    }
}
