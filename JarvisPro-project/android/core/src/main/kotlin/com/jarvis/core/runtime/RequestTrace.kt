package com.jarvis.core.runtime

/**
 * V2-01: request-level telemetry. Один [requestId] на весь user turn.
 */
data class RequestTrace(
    val requestId: String,
    val timestampMillis: Long,
    val inputType: String,
    val inputPreview: String = "",
    var route: RouteDecision = RouteDecision.UNKNOWN,
    var routeReason: String = "",
    var provider: String? = null,
    var model: String? = null,
    var fallback: Boolean = false,
    var skill: String? = null,
    var tool: String? = null,
    var planner: Boolean = false,
    var agent: Boolean = false,
    var verification: String? = null,
    var result: String? = null,
    var totalLatencyMs: Long = 0L,
    /** V2-02 semantic fields */
    var rawInput: String? = null,
    var normalizedInput: String? = null,
    var semanticIntent: String? = null,
    var semanticGoal: String? = null,
    var semanticConfidence: Float? = null,
    var ambiguity: String? = null,
    var llmInterpretationUsed: Boolean = false,
    var llmRequired: Boolean = false,
    /** V2-03 AI router fields */
    var aiSelectionReason: String? = null,
    var aiLatencyMs: Long? = null,
    var aiTtftMs: Long? = null,
    var aiCandidates: String? = null,
    /** V2-04 context telemetry */
    var contextSections: String? = null,
    var contextChars: Int? = null,
    var memoryCount: Int? = null,
    var historyTurns: Int? = null,
    var systemPromptVersion: String? = null,
    /** V2-05 structured AI output */
    var structured: Boolean? = null,
    var structuredSchemaVersion: String? = null,
    var structuredResponseType: String? = null,
    var structuredParse: String? = null,
    var structuredValidation: String? = null,
    var structuredRepairAttempted: Boolean? = null,
    var structuredRepairResult: String? = null,
    var structuredValidationReason: String? = null,
    /** V2-06 latency fields (ms). aiTtftMs is REAL TTFT only. */
    var e2eMs: Long? = null,
    var firstAckMs: Long? = null,
    var firstUsefulMs: Long? = null,
    var firstAudioMs: Long? = null,
    var completeMs: Long? = null,
    var asrMs: Long? = null,
    var semanticMs: Long? = null,
    var contextMs: Long? = null,
    var routeMs: Long? = null,
    var aiWaitMs: Long? = null,
    var aiFirstByteMs: Long? = null,
    var aiTtftKind: String? = null,
    var aiCompletionMs: Long? = null,
    var parseMs: Long? = null,
    var plannerMs: Long? = null,
    var toolMs: Long? = null,
    var verifyMs: Long? = null,
    var ttsStartupMs: Long? = null,
    var ttsFirstAudioMs: Long? = null,
    var coldStart: Boolean? = null,
    var warmState: String? = null,
    var cancelled: Boolean? = null,
    var streamingUsed: Boolean? = null,
    /** V2-07 streaming telemetry */
    var streamRequested: Boolean? = null,
    var streamSupported: Boolean? = null,
    var streamFallback: Boolean? = null,
    var streamFallbackReason: String? = null,
    var firstTextChunkMs: Long? = null,
    var firstSentenceMs: Long? = null,
    /** V2-08 ASR telemetry */
    var asrProvider: String? = null,
    var asrModel: String? = null,
    var asrRoute: String? = null,
    var asrRouteReason: String? = null,
    var asrFirstPartialMs: Long? = null,
    var asrFinalizeMs: Long? = null,
    var asrSetupMs: Long? = null,
    var asrTotalMs: Long? = null,
    var asrFallback: Boolean? = null,
    var asrFallbackReason: String? = null,
    /** V2-09 TTS telemetry */
    var ttsProvider: String? = null,
    var ttsModel: String? = null,
    var ttsVoice: String? = null,
    var ttsRoute: String? = null,
    var ttsRouteReason: String? = null,
    var ttsFallback: Boolean? = null,
    var ttsStreaming: Boolean? = null,
    var ttsSessionId: String? = null,
    var ttsSetupMs: Long? = null,
    var ttsFirstPlaybackMs: Long? = null,
    var ttsSynthesisMs: Long? = null,
    var ttsPlaybackMs: Long? = null,
    var ttsTotalMs: Long? = null,
    var ttsQueueDepth: Int? = null,
    var ttsCancelled: Boolean? = null,
    var ttsErrorCategory: String? = null,
    /** Attached interaction latency bag (optional). */
    var interactionLatency: InteractionLatency? = null,
    val stepIds: MutableList<String> = mutableListOf(),
) {
    fun childStepId(index: Int): String {
        val id = "$requestId/step-$index"
        stepIds += id
        return id
    }

    fun summaryLine(): String =
        "req=$requestId route=$route reason=$routeReason " +
            "goal=${semanticGoal ?: "-"} intent=${semanticIntent ?: "-"} " +
            "conf=${semanticConfidence ?: "-"} amb=${ambiguity ?: "-"} " +
            "llmInterp=$llmInterpretationUsed llmReq=$llmRequired " +
            "provider=${provider ?: "-"} model=${model ?: "-"} " +
            "sel=${aiSelectionReason ?: "-"} fallback=$fallback " +
            "aiLat=${aiLatencyMs ?: "-"} ttft=${aiTtftMs ?: "-"} ttftKind=${aiTtftKind ?: "-"} " +
            "aiDone=${aiCompletionMs ?: "-"} " +
            "e2e=${e2eMs ?: "-"} ack=${firstAckMs ?: "-"} useful=${firstUsefulMs ?: "-"} " +
            "audio=${firstAudioMs ?: "-"} done=${completeMs ?: "-"} " +
            "asr=${asrMs ?: "-"} asrProv=${asrProvider ?: "-"} asrModel=${asrModel ?: "-"} " +
            "asrRoute=${asrRoute ?: "-"} asrReason=${asrRouteReason ?: "-"} " +
            "asrSetup=${asrSetupMs ?: "-"} asrPartial=${asrFirstPartialMs ?: "-"} " +
            "asrFinal=${asrFinalizeMs ?: "-"} asrTotal=${asrTotalMs ?: "-"} " +
            "asrFb=${asrFallback ?: "-"}/${asrFallbackReason ?: "-"} " +
            "sem=${semanticMs ?: "-"} ctx=${contextMs ?: "-"} routeMs=${routeMs ?: "-"} " +
            "ttsUp=${ttsStartupMs ?: "-"} ttsProv=${ttsProvider ?: "-"} ttsModel=${ttsModel ?: "-"} " +
            "ttsVoice=${ttsVoice ?: "-"} ttsRoute=${ttsRoute ?: "-"} ttsReason=${ttsRouteReason ?: "-"} " +
            "ttsFb=${ttsFallback ?: "-"} ttsStream=${ttsStreaming ?: "-"} " +
            "ttsSetup=${ttsSetupMs ?: "-"} ttsAudio=${ttsFirstAudioMs ?: "-"} " +
            "ttsPlay=${ttsFirstPlaybackMs ?: "-"} ttsSynth=${ttsSynthesisMs ?: "-"} " +
            "ttsTotal=${ttsTotalMs ?: "-"} ttsQ=${ttsQueueDepth ?: "-"} " +
            "ttsCancel=${ttsCancelled ?: "-"} ttsErr=${ttsErrorCategory ?: "-"} " +
            "warm=${warmState ?: "-"} cold=${coldStart ?: "-"} cancelled=${cancelled ?: "-"} " +
            "stream=${streamingUsed ?: "-"} streamReq=${streamRequested ?: "-"} " +
            "streamOk=${streamSupported ?: "-"} streamFb=${streamFallback ?: "-"} " +
            "streamFbReason=${streamFallbackReason ?: "-"} " +
            "firstChunk=${firstTextChunkMs ?: "-"} firstSent=${firstSentenceMs ?: "-"} " +
            "ctx=${contextSections ?: "-"} ctxChars=${contextChars ?: "-"} memN=${memoryCount ?: "-"} " +
            "hist=${historyTurns ?: "-"} sysVer=${systemPromptVersion ?: "-"} " +
            "structured=${structured ?: "-"} schema=${structuredSchemaVersion ?: "-"} " +
            "stype=${structuredResponseType ?: "-"} parse=${structuredParse ?: "-"} " +
            "sval=${structuredValidation ?: "-"} repair=${structuredRepairAttempted ?: "-"} " +
            "repairRes=${structuredRepairResult ?: "-"} sreason=${structuredValidationReason ?: "-"} " +
            "skill=${skill ?: "-"} tool=${tool ?: "-"} " +
            "planner=$planner agent=$agent verify=${verification ?: "-"} " +
            "result=${result ?: "-"} latency=${totalLatencyMs}ms"
}

/** Apply V2-05 structured diagnostics onto a request trace (no secrets). */
fun RequestTrace.applyStructured(d: com.jarvis.core.aioutput.StructuredAiDiagnostics) {
    structured = d.structured
    structuredSchemaVersion = d.schemaVersion
    structuredResponseType = d.responseType
    structuredParse = d.parse.name
    structuredValidation = d.validation.name
    structuredRepairAttempted = d.repairAttempted
    structuredRepairResult = d.repairResult
    structuredValidationReason = d.validationReason
}

object RequestTraceLog {
    @Volatile
    var sink: ((RequestTrace) -> Unit)? = null

    fun emit(trace: RequestTrace) {
        runCatching {
            android.util.Log.i("Jarvis.Trace", trace.summaryLine())
        }
        runCatching {
            trace.interactionLatency?.let {
                android.util.Log.i("Jarvis.Latency", it.summaryLine())
            }
        }
        runCatching { LatencyStatsStore.recordFrom(trace) }
        sink?.invoke(trace)
    }
}
