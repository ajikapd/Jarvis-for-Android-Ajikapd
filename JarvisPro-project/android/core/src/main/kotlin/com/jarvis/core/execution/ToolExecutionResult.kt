package com.jarvis.core.execution

import java.util.UUID

/**
 * Stage 8E: статус результата выполнения.
 * Не смешивать с [com.jarvis.core.autonomy.AutonomyVerdictType].
 */
enum class ToolExecutionStatus {
    SUCCESS,
    FAILURE,
}

/**
 * Immutable результат выполнения Tool.
 * Отвечает: «что произошло при попытке выполнения?» — не «можно ли выполнять?».
 */
data class ToolExecutionResult(
    val requestId: String,
    val toolId: String,
    val status: ToolExecutionStatus,
    val output: Map<String, String> = emptyMap(),
    val error: ToolError? = null,
    val metadata: ToolMetadata = ToolMetadata(),
    val executionId: String = UUID.randomUUID().toString(),
    val durationMs: Long = 0L,
) {
    init {
        require(requestId.isNotBlank()) { "requestId blank" }
        require(toolId.isNotBlank()) { "toolId blank" }
        require(output.size <= MAX_OUTPUT) { "too many output fields" }
        output.forEach { (k, v) ->
            require(k.length <= MAX_KEY) { "output key too long" }
            require(v.length <= MAX_VALUE) { "output value too long" }
        }
        when (status) {
            ToolExecutionStatus.SUCCESS ->
                require(error == null) { "SUCCESS must not carry ToolError" }
            ToolExecutionStatus.FAILURE ->
                require(error != null) { "FAILURE requires ToolError" }
        }
    }

    companion object {
        const val MAX_OUTPUT = 24
        const val MAX_KEY = 48
        const val MAX_VALUE = 256

        fun success(
            requestId: String,
            toolId: String,
            output: Map<String, String> = emptyMap(),
            metadata: ToolMetadata = ToolMetadata(),
            durationMs: Long = 0L,
        ): ToolExecutionResult = ToolExecutionResult(
            requestId = requestId,
            toolId = toolId,
            status = ToolExecutionStatus.SUCCESS,
            output = output,
            error = null,
            metadata = metadata,
            durationMs = durationMs,
        )

        fun failure(
            requestId: String,
            toolId: String,
            error: ToolError,
            metadata: ToolMetadata = ToolMetadata(),
            durationMs: Long = 0L,
        ): ToolExecutionResult = ToolExecutionResult(
            requestId = requestId,
            toolId = toolId,
            status = ToolExecutionStatus.FAILURE,
            output = emptyMap(),
            error = error,
            metadata = metadata,
            durationMs = durationMs,
        )
    }
}
