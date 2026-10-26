package com.jarvis.core.autonomy

/**
 * Stage 8A/8B: предложенное действие **до** ToolCall.
 *
 * [kind] — typed category для [ActionRiskClassifier] (не severity).
 * Severity — только из [ActionRiskAssessment.riskClass].
 *
 * [toolId] — опциональная привязка к ToolRegistry (не раньше 8E).
 * Не вызывает ToolExecutor.
 *
 * [arguments] — только bounded string map; без raw notification/screen/secrets.
 */
data class ProposedAction(
    val actionId: String,
    val kind: ActionKind,
    val displaySummary: String,
    val toolId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
) {
    init {
        require(actionId.isNotBlank()) { "actionId blank" }
        require(displaySummary.length <= MAX_SUMMARY) { "displaySummary too long" }
        require(arguments.size <= MAX_ARGS) { "too many arguments" }
        arguments.forEach { (k, v) ->
            require(k.length <= MAX_KEY) { "arg key too long" }
            require(v.length <= MAX_VALUE) { "arg value too long" }
        }
    }

    companion object {
        const val MAX_SUMMARY = 160
        const val MAX_ARGS = 12
        const val MAX_KEY = 48
        const val MAX_VALUE = 96
    }
}
