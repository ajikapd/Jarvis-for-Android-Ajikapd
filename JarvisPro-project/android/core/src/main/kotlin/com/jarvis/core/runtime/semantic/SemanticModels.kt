package com.jarvis.core.runtime.semantic

import com.jarvis.core.runtime.RouteDecision

/**
 * V2-02: semantic understanding result (не ToolCall, не execution).
 */
enum class AmbiguityLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

enum class SemanticClarity {
    CLEAR,
    PARTIAL,
    UNCLEAR,
}

data class SemanticEntity(
    val name: String,
    val value: String,
    val role: String = "",
)

/**
 * Структурированное понимание пользовательской фразы.
 * Не исполняет действия и не ходит в SafetyGate.
 */
data class SemanticUnderstanding(
    val rawInput: String,
    val normalizedInput: String,
    /** Краткая цель, напр. reduce_audio_volume / open_chat. */
    val goal: String,
    /** Совместимый intent id (flashlight, volume, chat, plan, …). */
    val intent: String,
    val entities: List<SemanticEntity> = emptyList(),
    val arguments: Map<String, String> = emptyMap(),
    val constraints: List<String> = emptyList(),
    val contextRefs: List<String> = emptyList(),
    val conversationRefs: List<String> = emptyList(),
    val confidence: Float,
    val ambiguity: AmbiguityLevel,
    val clarity: SemanticClarity,
    val requiredCapabilities: List<String> = emptyList(),
    /** Предлагаемый route — финальный выбор делает [SemanticRouteSelector]. */
    val suggestedRoute: RouteDecision,
    val routeReason: String,
    val clarifyQuestion: String? = null,
    val candidateInterpretations: List<String> = emptyList(),
    /** Переписанная команда для legacy BrainRouter (опционально). */
    val rewrittenCommand: String? = null,
    val llmInterpretationUsed: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    init {
        require(confidence in 0f..1f)
        require(goal.isNotBlank())
        require(intent.isNotBlank())
    }
}

/**
 * Вход semantic layer (минимальный relevant context).
 */
data class SemanticInput(
    val rawText: String,
    val normalizedText: String,
    val previousUserText: String? = null,
    val previousAssistantText: String? = null,
    val lastToolId: String? = null,
    val lastToolArgs: Map<String, String> = emptyMap(),
    val pendingPhase: String? = null,
    val hasPendingAction: Boolean = false,
)

fun interface SemanticInterpreter {
    fun interpret(input: SemanticInput): SemanticUnderstanding?
}
