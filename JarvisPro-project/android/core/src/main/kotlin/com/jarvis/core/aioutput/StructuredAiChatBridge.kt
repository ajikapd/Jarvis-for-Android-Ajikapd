package com.jarvis.core.aioutput

/**
 * Bridge helpers for optional CHAT structured wrap without forcing natural dialogue into JSON.
 */
object StructuredAiChatBridge {

    /**
     * If raw looks like schema v1 JSON → validate as ANSWER/CLARIFICATION/…
     * Else → synthetic ANSWER envelope (CHAT free-text allowed).
     */
    fun wrapChatText(raw: String, requestId: String? = null): StructuredAiProcessResult {
        val pipeline = StructuredAiOutputPipeline()
        val asJson = pipeline.process(raw, StructuredAiTask.CHAT, requestId)
        if (asJson is StructuredAiProcessResult.Valid) return asJson
        val text = raw.trim()
        if (text.isBlank()) {
            return StructuredAiProcessResult.Invalid(
                StructuredAiDiagnostics(
                    structured = false,
                    schemaVersion = null,
                    responseType = null,
                    parse = StructuredParseStatus.FAIL,
                    validation = StructuredValidationStatus.FAIL,
                    validationReason = "empty_chat",
                ),
                "",
            )
        }
        return StructuredAiProcessResult.Valid(
            StructuredAiEnvelope(
                schemaVersion = StructuredAiLimits.SCHEMA_VERSION,
                responseType = StructuredAiResponseType.ANSWER,
                requestId = requestId,
                text = text.take(StructuredAiLimits.MAX_TEXT_CHARS),
            ),
            StructuredAiDiagnostics(
                structured = false,
                schemaVersion = StructuredAiLimits.SCHEMA_VERSION,
                responseType = StructuredAiResponseType.ANSWER.name,
                parse = StructuredParseStatus.SUCCESS,
                validation = StructuredValidationStatus.SUCCESS,
                validationReason = "chat_free_text",
            ),
        )
    }
}
