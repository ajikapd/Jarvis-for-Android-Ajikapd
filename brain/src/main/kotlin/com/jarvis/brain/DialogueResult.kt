package com.jarvis.brain

/**
 * V2-01: outcome диалога с явной классификацией (не выдаём template за LLM).
 * V2-03: provider/model/selectionReason/fallback для AIRouter telemetry.
 */
data class DialogueResult(
    val text: String,
    val kind: Kind,
    val reason: String = "",
    val providerHint: String? = null,
    val modelHint: String? = null,
    val selectionReason: String? = null,
    val fallbackUsed: Boolean = false,
    val latencyMs: Long? = null,
    val ttftMs: Long? = null,
    val ttftKind: com.jarvis.core.runtime.TtftKind? = null,
    val contextDiagnostics: com.jarvis.core.llm.ContextBuildDiagnostics? = null,
) {
    enum class Kind {
        TEMPLATE,
        LLM,
        FALLBACK_TEMPLATE,
    }
}
