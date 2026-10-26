package com.jarvis.core.execution

/**
 * Stage 8E: категории ошибок выполнения Tool.
 * Не путать с [com.jarvis.core.autonomy.AutonomyReasonCode] / SafetyGate failures.
 */
enum class ToolErrorCategory {
    INVALID_INPUT,
    UNAVAILABLE,
    PERMISSION_FAILURE,
    EXECUTION_FAILURE,
    TIMEOUT,
    CANCELLED,
    UNSUPPORTED,
    INTERNAL_ERROR,
}

/**
 * Структурированная ошибка Tool execution.
 */
data class ToolError(
    val category: ToolErrorCategory,
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "ToolError.code blank" }
        require(message.length <= MAX_MESSAGE) { "ToolError.message too long" }
        require(details.size <= MAX_DETAILS) { "too many ToolError details" }
        details.forEach { (k, v) ->
            require(k.length <= MAX_KEY) { "ToolError detail key too long" }
            require(v.length <= MAX_VALUE) { "ToolError detail value too long" }
        }
    }

    companion object {
        const val MAX_MESSAGE = 240
        const val MAX_DETAILS = 8
        const val MAX_KEY = 48
        const val MAX_VALUE = 96

        fun unsupported(message: String = "Executor not implemented"): ToolError =
            ToolError(
                category = ToolErrorCategory.UNSUPPORTED,
                code = "EXECUTOR_NOT_IMPLEMENTED",
                message = message,
            )

        fun invalidInput(message: String, code: String = "INVALID_INPUT"): ToolError =
            ToolError(ToolErrorCategory.INVALID_INPUT, code, message)

        fun unavailable(message: String): ToolError =
            ToolError(ToolErrorCategory.UNAVAILABLE, "TOOL_UNAVAILABLE", message)

        fun permissionFailure(message: String): ToolError =
            ToolError(ToolErrorCategory.PERMISSION_FAILURE, "PERMISSION_FAILURE", message)

        fun executionFailure(message: String): ToolError =
            ToolError(ToolErrorCategory.EXECUTION_FAILURE, "EXECUTION_FAILURE", message)

        fun timeout(message: String = "Tool timed out"): ToolError =
            ToolError(ToolErrorCategory.TIMEOUT, "TIMEOUT", message)

        fun cancelled(message: String = "Tool cancelled"): ToolError =
            ToolError(ToolErrorCategory.CANCELLED, "CANCELLED", message)

        fun internal(message: String): ToolError =
            ToolError(ToolErrorCategory.INTERNAL_ERROR, "INTERNAL_ERROR", message)
    }
}
