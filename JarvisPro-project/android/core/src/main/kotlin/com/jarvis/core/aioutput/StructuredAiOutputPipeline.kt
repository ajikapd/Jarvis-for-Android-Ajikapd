package com.jarvis.core.aioutput

import com.jarvis.core.execution.CatalogToolRegistry

/**
 * V2-05: parse → schema → semantic. Optional one-shot repair hook.
 * Never executes tools.
 */
class StructuredAiOutputPipeline(
    private val toolRegistry: CatalogToolRegistry? = null,
) {

    fun process(
        raw: String,
        task: StructuredAiTask,
        requestId: String? = null,
        repairedRaw: String? = null,
        repairAttempted: Boolean = false,
        repairResult: String? = null,
    ): StructuredAiProcessResult {
        val first = processOnce(raw, task, requestId, repairAttempted, repairResult)
        if (first is StructuredAiProcessResult.Valid) return first
        if (repairedRaw != null && !repairAttempted) {
            // Caller already supplied repaired text — validate as second attempt.
            return processOnce(
                repairedRaw,
                task,
                requestId,
                repairAttempted = true,
                repairResult = repairResult ?: "supplied",
            )
        }
        return first
    }

    /**
     * Bounded repair: at most [StructuredAiLimits.MAX_REPAIR_ATTEMPTS].
     * [repairFn] must return corrected model text or null.
     */
    fun processWithRepair(
        raw: String,
        task: StructuredAiTask,
        requestId: String? = null,
        repairFn: ((failedReason: String, original: String) -> String?)? = null,
    ): StructuredAiProcessResult {
        val first = processOnce(raw, task, requestId, repairAttempted = false, repairResult = null)
        if (first is StructuredAiProcessResult.Valid) return first
        if (repairFn == null || StructuredAiLimits.MAX_REPAIR_ATTEMPTS < 1) return first
        val reason = (first as StructuredAiProcessResult.Invalid).diagnostics.validationReason
            ?: first.diagnostics.parse.name
        val repaired = runCatching { repairFn(reason, raw) }.getOrNull()
        if (repaired.isNullOrBlank()) {
            return StructuredAiProcessResult.Invalid(
                first.diagnostics.copy(
                    repairAttempted = true,
                    repairResult = "empty",
                ),
                first.rawPreview,
            )
        }
        return processOnce(
            repaired,
            task,
            requestId,
            repairAttempted = true,
            repairResult = "attempted",
        ).let { second ->
            when (second) {
                is StructuredAiProcessResult.Valid -> second.copy(
                    diagnostics = second.diagnostics.copy(
                        repairAttempted = true,
                        repairResult = "SUCCESS",
                    ),
                )
                is StructuredAiProcessResult.Invalid -> second.copy(
                    diagnostics = second.diagnostics.copy(
                        repairAttempted = true,
                        repairResult = "FAIL",
                    ),
                )
            }
        }
    }

    private fun processOnce(
        raw: String,
        task: StructuredAiTask,
        requestId: String?,
        repairAttempted: Boolean,
        repairResult: String?,
    ): StructuredAiProcessResult {
        val preview = raw.trim().take(120)
        val syntax = StructuredAiOutputParser.extractJsonObject(raw)
        if (syntax is ParseSyntaxResult.Fail) {
            return StructuredAiProcessResult.Invalid(
                StructuredAiDiagnostics(
                    structured = task != StructuredAiTask.CHAT,
                    schemaVersion = null,
                    responseType = null,
                    parse = StructuredParseStatus.FAIL,
                    validation = StructuredValidationStatus.SKIPPED,
                    repairAttempted = repairAttempted,
                    repairResult = repairResult,
                    validationReason = syntax.reason,
                    parseMs = syntax.parseMs,
                ),
                preview,
            )
        }
        syntax as ParseSyntaxResult.Ok
        when (val schema = StructuredAiSchemaValidator.validate(syntax.json)) {
            is SchemaValidationResult.Fail ->
                return StructuredAiProcessResult.Invalid(
                    StructuredAiDiagnostics(
                        structured = true,
                        schemaVersion = stringField(syntax.json, "schemaVersion"),
                        responseType = stringField(syntax.json, "type"),
                        parse = StructuredParseStatus.SUCCESS,
                        validation = StructuredValidationStatus.FAIL,
                        repairAttempted = repairAttempted,
                        repairResult = repairResult,
                        validationReason = schema.reason,
                        parseMs = syntax.parseMs,
                    ),
                    preview,
                )
            is SchemaValidationResult.Ok -> {
                var env = schema.envelope
                if (requestId != null && env.requestId == null) {
                    env = env.copy(requestId = requestId)
                }
                return when (val sem = StructuredAiSemanticValidator.validate(env, toolRegistry, task)) {
                    is SemanticValidationResult.Fail ->
                        StructuredAiProcessResult.Invalid(
                            StructuredAiDiagnostics(
                                structured = true,
                                schemaVersion = env.schemaVersion,
                                responseType = env.responseType.name,
                                parse = StructuredParseStatus.SUCCESS,
                                validation = StructuredValidationStatus.FAIL,
                                repairAttempted = repairAttempted,
                                repairResult = repairResult,
                                validationReason = sem.reason,
                                parseMs = syntax.parseMs,
                            ),
                            preview,
                        )
                    is SemanticValidationResult.Ok ->
                        StructuredAiProcessResult.Valid(
                            sem.envelope,
                            StructuredAiDiagnostics(
                                structured = true,
                                schemaVersion = env.schemaVersion,
                                responseType = env.responseType.name,
                                parse = StructuredParseStatus.SUCCESS,
                                validation = StructuredValidationStatus.SUCCESS,
                                repairAttempted = repairAttempted,
                                repairResult = repairResult,
                                validationReason = null,
                                parseMs = syntax.parseMs,
                            ),
                        )
                }
            }
        }
    }

    private fun stringField(obj: JsonVal.Obj, key: String): String? =
        obj.opt(key)?.asString()?.ifBlank { null }

    companion object {
        /** Prompt fragment instructing models to emit schema v1 JSON only. */
        fun jsonInstruction(task: StructuredAiTask): String = when (task) {
            StructuredAiTask.SEMANTIC_INTERPRETATION ->
                """Ответь ТОЛЬКО JSON: {"schemaVersion":"1","type":"ANSWER|CLARIFICATION|PLAN|TOOL_REQUEST|NO_ACTION","suggestedRoute":"FAST_SKILL|CHAT|PLAN|CLARIFY|TEMPLATE","goal":"...","intent":"...","ambiguity":"NONE|LOW|MEDIUM|HIGH","text":"...","question":"...","reason":"AMBIGUOUS_TARGET|MISSING_SLOT|MULTIPLE_CANDIDATES|UNSURE_INTENT|OTHER"} без markdown."""
            StructuredAiTask.TOOL_REQUEST ->
                """Ответь ТОЛЬКО JSON: {"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"...","arguments":{},"goal":"..."} без markdown. Не исполняй действие."""
            StructuredAiTask.PLAN ->
                """Ответь ТОЛЬКО JSON: {"schemaVersion":"1","type":"PLAN","goal":"...","steps":[{"stepId":"s1","kind":"TOOL","toolId":"...","parameters":{}}]} без markdown. Не исполняй."""
            StructuredAiTask.CHAT ->
                """Если нужен structured ответ: {"schemaVersion":"1","type":"ANSWER","text":"..."} иначе обычный текст."""
        }

        fun repairInstruction(failedReason: String): String =
            "Исправь предыдущий ответ в валидный JSON schemaVersion=1. Ошибка: $failedReason. Только JSON, без пояснений."
    }
}
