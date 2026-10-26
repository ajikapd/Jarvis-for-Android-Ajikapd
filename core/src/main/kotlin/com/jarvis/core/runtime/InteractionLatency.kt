package com.jarvis.core.runtime

/**
 * V2-06: honest TTFT classification.
 * Non-streaming providers must never report completion latency as real TTFT.
 */
enum class TtftKind {
    /** Measured time to first token/byte from a streaming provider. */
    REAL,
    /** Heuristic estimate only (not used as real TTFT in summaries). */
    ESTIMATED,
    /** Streaming not available / not measured. */
    NOT_AVAILABLE,
}

enum class WarmState {
    COLD,
    WARM,
    HOT,
    UNKNOWN,
}

/**
 * Mutable mark bag for one user interaction (voice or text).
 * Timestamps are elapsedRealtime millis relative to [anchorElapsedMs],
 * or absolute elapsedRealtime values stored in fields ending with At.
 */
class InteractionLatency(
    val requestId: String,
    val anchorElapsedMs: Long,
) {
    @Volatile var cancelled: Boolean = false
    @Volatile var coldStart: Boolean = false
    @Volatile var warmState: WarmState = WarmState.UNKNOWN
    @Volatile var fallback: Boolean = false

    // Absolute elapsedRealtime marks (nullable = not observed)
    var wakeDetectedAt: Long? = null
    var asrStartAt: Long? = null
    var asrPartialAt: Long? = null
    var asrFinalAt: Long? = null
    var normalizeStartAt: Long? = null
    var normalizeEndAt: Long? = null
    var semanticStartAt: Long? = null
    var semanticEndAt: Long? = null
    var routeStartAt: Long? = null
    var routeEndAt: Long? = null
    var contextStartAt: Long? = null
    var contextEndAt: Long? = null
    var aiRouteStartAt: Long? = null
    var aiRouteEndAt: Long? = null
    var llmRequestStartAt: Long? = null
    var llmFirstByteAt: Long? = null
    var llmFirstTokenAt: Long? = null
    var llmCompleteAt: Long? = null
    var structuredParseStartAt: Long? = null
    var structuredParseEndAt: Long? = null
    var plannerStartAt: Long? = null
    var plannerEndAt: Long? = null
    var toolStartAt: Long? = null
    var toolEndAt: Long? = null
    var verificationStartAt: Long? = null
    var verificationEndAt: Long? = null
    var ttsStartAt: Long? = null
    var ttsFirstAudioAt: Long? = null
    var ttsCompleteAt: Long? = null
    var firstAckAt: Long? = null
    var firstUsefulAt: Long? = null
    var interactionCompleteAt: Long? = null

    var ttftKind: TtftKind = TtftKind.NOT_AVAILABLE
    var streamingUsed: Boolean = false

    fun mark(now: Long = LatencyClock.elapsedMs()): Long = now

    fun sinceAnchor(at: Long?): Long? =
        at?.let { (it - anchorElapsedMs).coerceAtLeast(0L) }

    fun span(start: Long?, end: Long?): Long? {
        if (start == null || end == null) return null
        return (end - start).coerceAtLeast(0L)
    }

    /** User-visible: first acknowledgement (optional UX ack). */
    fun timeToFirstAcknowledgementMs(): Long? = sinceAnchor(firstAckAt)

    /** First useful textual/tool result ready (brain reply available). */
    fun timeToFirstUsefulResultMs(): Long? = sinceAnchor(firstUsefulAt)

    /** First audible TTS sample. */
    fun timeToFirstAudioMs(): Long? = sinceAnchor(ttsFirstAudioAt)

    fun timeToCompletionMs(): Long? = sinceAnchor(interactionCompleteAt)

    fun endToEndMs(): Long? = timeToCompletionMs() ?: timeToFirstAudioMs()

    fun asrMs(): Long? = span(asrStartAt, asrFinalAt)
    fun semanticMs(): Long? = span(semanticStartAt, semanticEndAt)
    fun contextMs(): Long? = span(contextStartAt, contextEndAt)
    fun routeMs(): Long? = span(routeStartAt, routeEndAt)
    fun aiRouteMs(): Long? = span(aiRouteStartAt, aiRouteEndAt)
    fun aiWaitMs(): Long? = span(llmRequestStartAt, llmCompleteAt)
    fun aiFirstByteMs(): Long? = span(llmRequestStartAt, llmFirstByteAt)
    fun realTtftMs(): Long? =
        if (ttftKind == TtftKind.REAL) span(llmRequestStartAt, llmFirstTokenAt ?: llmFirstByteAt) else null
    fun aiCompletionMs(): Long? = span(llmRequestStartAt, llmCompleteAt)
    fun parseMs(): Long? = span(structuredParseStartAt, structuredParseEndAt)
    fun plannerMs(): Long? = span(plannerStartAt, plannerEndAt)
    fun toolMs(): Long? = span(toolStartAt, toolEndAt)
    fun verifyMs(): Long? = span(verificationStartAt, verificationEndAt)
    fun ttsStartupMs(): Long? = span(ttsStartAt, ttsFirstAudioAt)
    fun ttsFirstAudioFromAnchorMs(): Long? = timeToFirstAudioMs()

    fun summaryLine(): String =
        "lat req=$requestId e2e=${endToEndMs() ?: "-"} " +
            "ack=${timeToFirstAcknowledgementMs() ?: "-"} " +
            "useful=${timeToFirstUsefulResultMs() ?: "-"} " +
            "audio=${timeToFirstAudioMs() ?: "-"} " +
            "done=${timeToCompletionMs() ?: "-"} " +
            "asr=${asrMs() ?: "-"} sem=${semanticMs() ?: "-"} ctx=${contextMs() ?: "-"} " +
            "route=${routeMs() ?: "-"} aiWait=${aiWaitMs() ?: "-"} " +
            "aiFB=${aiFirstByteMs() ?: "-"} realTtft=${realTtftMs() ?: "-"} " +
            "ttftKind=$ttftKind aiDone=${aiCompletionMs() ?: "-"} " +
            "parse=${parseMs() ?: "-"} plan=${plannerMs() ?: "-"} " +
            "tool=${toolMs() ?: "-"} verify=${verifyMs() ?: "-"} " +
            "ttsStart=${ttsStartupMs() ?: "-"} " +
            "warm=$warmState cold=$coldStart cancelled=$cancelled fallback=$fallback stream=$streamingUsed"
}

/**
 * Apply latency marks onto [RequestTrace] for Jarvis.Trace emission.
 */
fun RequestTrace.applyLatency(lat: InteractionLatency) {
    e2eMs = lat.endToEndMs()
    firstAckMs = lat.timeToFirstAcknowledgementMs()
    firstUsefulMs = lat.timeToFirstUsefulResultMs()
    firstAudioMs = lat.timeToFirstAudioMs()
    completeMs = lat.timeToCompletionMs()
    asrMs = lat.asrMs()
    semanticMs = lat.semanticMs()
    contextMs = lat.contextMs()
    routeMs = lat.routeMs()
    aiWaitMs = lat.aiWaitMs()
    aiFirstByteMs = lat.aiFirstByteMs()
    // Only publish real TTFT into aiTtftMs; never completion-as-TTFT
    aiTtftMs = lat.realTtftMs()
    aiTtftKind = lat.ttftKind.name
    aiCompletionMs = lat.aiCompletionMs()
    parseMs = lat.parseMs()
    plannerMs = lat.plannerMs()
    toolMs = lat.toolMs()
    verifyMs = lat.verifyMs()
    ttsStartupMs = lat.ttsStartupMs()
    ttsFirstAudioMs = lat.ttsFirstAudioFromAnchorMs()
    coldStart = lat.coldStart
    warmState = lat.warmState.name
    cancelled = lat.cancelled
    if (lat.fallback) fallback = true
    streamingUsed = lat.streamingUsed
    if (totalLatencyMs == 0L) {
        totalLatencyMs = lat.timeToCompletionMs()
            ?: lat.timeToFirstUsefulResultMs()
            ?: 0L
    }
}
