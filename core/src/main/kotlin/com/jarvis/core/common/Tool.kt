package com.jarvis.core.common

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult

/**
 * Атомарный инструмент (skill). Один инструмент = одна ответственность:
 * input → validate → execute → ToolResult (раздел 22 AGENT_START).
 */
interface Tool {
    val definition: ToolDefinition
    fun execute(call: ToolCall): ToolResult
}

/** Реестр инструментов. Живёт в памяти процесса, наполняется при старте Brain-а. */
class ToolRegistry {
    private val byId = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        byId[tool.definition.id] = tool
    }

    fun unregister(id: String) {
        byId.remove(id)
    }

    fun find(id: String): Tool? = byId[id]

    fun available(): List<Tool> = byId.values.toList()
}

/** Выполняет инструмент по id, добавляя замер времени и обработку ошибок. */
interface ToolExecutor {
    val registry: ToolRegistry
    fun execute(call: ToolCall): ToolResult
}

class DefaultToolExecutor(
    override val registry: ToolRegistry
) : ToolExecutor {

    override fun execute(call: ToolCall): ToolResult {
        val tool = registry.find(call.toolId)
            ?: return ToolResult(
                requestId = call.requestId,
                toolId = call.toolId,
                status = com.jarvis.core.common.model.ToolResultStatus.FAILED,
                message = "Инструмент не найден: ${call.toolId}"
            )

        val started = System.nanoTime()
        return try {
            tool.execute(call).copy(durationMs = (System.nanoTime() - started) / 1_000_000)
        } catch (t: Throwable) {
            com.jarvis.core.common.model.ToolResult(
                requestId = call.requestId,
                toolId = call.toolId,
                status = com.jarvis.core.common.model.ToolResultStatus.FAILED,
                message = "Ошибка при выполнении «${tool.definition.name}»: ${t.message ?: t.javaClass.simpleName}",
                durationMs = (System.nanoTime() - started) / 1_000_000
            )
        }
    }
}