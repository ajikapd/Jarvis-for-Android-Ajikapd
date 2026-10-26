package com.jarvis.core.execution

/**
 * Stage 8E: контракт будущего Executor.
 *
 * **Не** реализует Android side effects на этом этапе.
 * Реальная реализация — за пределами 8E (roadmap 8F+/14).
 */
fun interface FutureToolExecutor {
    fun execute(request: ToolExecutionRequest): ToolExecutionResult
}

/**
 * Stub Executor: всегда FAILURE/UNSUPPORTED, без side effects.
 * Нужен для архитектурной границы и unit tests.
 */
object UnimplementedFutureToolExecutor : FutureToolExecutor {
    override fun execute(request: ToolExecutionRequest): ToolExecutionResult =
        ToolExecutionResult.failure(
            requestId = request.id,
            toolId = request.toolId,
            error = ToolError.unsupported(
                "Stage 8E: FutureToolExecutor stub — no real execution",
            ),
            metadata = request.metadata,
        )
}
