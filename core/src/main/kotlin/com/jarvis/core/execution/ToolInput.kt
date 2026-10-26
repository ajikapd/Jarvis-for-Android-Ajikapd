package com.jarvis.core.execution

import com.jarvis.core.autonomy.ProposedAction

/**
 * Stage 8E: типизированный вход Tool.
 * Bounded string map — без `Map<String, Any>` и без права менять
 * ActionKind / risk / verdict / gate result.
 */
data class ToolInput(
    val parameters: Map<String, String> = emptyMap(),
) {
    init {
        require(parameters.size <= ProposedAction.MAX_ARGS) { "too many ToolInput parameters" }
        parameters.forEach { (k, v) ->
            require(k.isNotBlank()) { "ToolInput key blank" }
            require(k.length <= ProposedAction.MAX_KEY) { "ToolInput key too long" }
            require(v.length <= ProposedAction.MAX_VALUE) { "ToolInput value too long" }
        }
    }

    fun matches(action: ProposedAction): Boolean = parameters == action.arguments

    companion object {
        fun fromProposedAction(action: ProposedAction): ToolInput =
            ToolInput(parameters = action.arguments)

        fun empty(): ToolInput = ToolInput()
    }
}
