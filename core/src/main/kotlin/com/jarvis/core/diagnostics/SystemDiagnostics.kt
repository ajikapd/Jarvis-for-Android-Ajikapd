package com.jarvis.core.diagnostics

/**
 * Stage 27: Advanced UI — снимок состояния системы для dashboard (без дублирования core-логики).
 */
data class SystemDiagnosticsSnapshot(
    val capturedAtMillis: Long,
    val agentLoopPresent: Boolean,
    val plannerPresent: Boolean,
    val aiRouterPresent: Boolean,
    val cloudKeyConfigured: Boolean,
    val preferCloud: Boolean,
    val providersLocal: Int,
    val providersCloud: Int,
    val providersAvailable: Int,
    val catalogSize: Int,
    val discoveryOn: Boolean,
    val routinesSaved: Int,
    val memory2Durable: Int,
    val remoteDevices: Int,
    val visionAvailable: Boolean,
    val asrRoute: String,
    val ttsRoute: String,
    val notes: List<String> = emptyList(),
    /** V2-01: truthful feature lifecycle lines (constructed ≠ active). */
    val featureStatuses: List<String> = emptyList(),
) {
    fun lines(): List<String> = listOf(
        "agentLoop=$agentLoopPresent planner=$plannerPresent router=$aiRouterPresent",
        "cloudKey=$cloudKeyConfigured preferCloud=$preferCloud",
        "providers local=$providersLocal cloud=$providersCloud available=$providersAvailable",
        "catalog=$catalogSize discovery=$discoveryOn",
        "routines=$routinesSaved memory2Durable=$memory2Durable remote=$remoteDevices",
        "vision=$visionAvailable asr=$asrRoute tts=$ttsRoute",
    ) + featureStatuses.map { "feature $it" } + notes
}

object DiagnosticsFormatter {
    fun render(snapshot: SystemDiagnosticsSnapshot): String =
        buildString {
            appendLine("JARVIS DIAGNOSTICS")
            appendLine("at=${snapshot.capturedAtMillis}")
            snapshot.lines().forEach { appendLine(it) }
        }
}
