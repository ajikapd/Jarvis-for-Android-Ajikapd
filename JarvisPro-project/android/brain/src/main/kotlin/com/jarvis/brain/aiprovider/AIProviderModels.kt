package com.jarvis.brain.aiprovider

/**
 * Stage 10: тип провайдера AI.
 * Cloud не обязателен — Local должен работать offline.
 */
enum class AIProviderKind {
    LOCAL,
    CLOUD,
}

/**
 * Stage 10: доступность провайдера (не autonomy / risk).
 */
enum class AIProviderAvailability {
    AVAILABLE,
    UNAVAILABLE,
    LOADING,
    DEGRADED,
}

/**
 * Декларативные возможности провайдера.
 * Не повышают autonomy и не обходят Policy/SafetyGate.
 */
data class AIProviderCapabilities(
    val supportsStreaming: Boolean = false,
    val supportsCancellation: Boolean = false,
    val supportsStructuredDecide: Boolean = true,
    val maxContextTokens: Int = 0,
    val maxOutputTokens: Int = 0,
    val offlineCapable: Boolean = false,
    val requiresNetwork: Boolean = false,
    val notes: List<String> = emptyList(),
    /** V2-03 model metadata defaults for this provider. */
    val defaultModelId: String = "",
    val qualityTier: QualityRequirement = QualityRequirement.BALANCED,
    val speedTier: QualityRequirement = QualityRequirement.BALANCED,
    val privacyClass: PrivacyLevel = PrivacyLevel.NORMAL,
) {
    fun toModelDescriptor(providerId: String, modelId: String = defaultModelId): ModelDescriptor =
        ModelDescriptor(
            providerId = providerId,
            modelId = modelId.ifBlank { defaultModelId.ifBlank { providerId } },
            qualityTier = qualityTier,
            speedTier = speedTier,
            contextLimit = maxContextTokens,
            streaming = supportsStreaming,
            structuredOutput = supportsStructuredDecide,
            onlineOnly = requiresNetwork,
            privacyClass = privacyClass,
        )
}

enum class AIProviderErrorCategory {
    UNAVAILABLE,
    NOT_READY,
    TIMEOUT,
    CANCELLED,
    INVALID_REQUEST,
    GENERATION_FAILED,
    NETWORK,
    INTERNAL,
}

data class AIProviderError(
    val category: AIProviderErrorCategory,
    val code: String,
    val message: String,
) {
    init {
        require(code.isNotBlank()) { "error code blank" }
        require(message.length <= 240) { "error message too long" }
    }
}

/**
 * Запрос к AIProvider. Не ToolExecutionRequest и не AutonomyVerdict.
 */
data class AICompletionRequest(
    val requestId: String,
    val userText: String,
    val history: List<String> = emptyList(),
    val memories: String = "",
    val persona: String = "CLASSIC",
    val wantStructured: Boolean = false,
    val cancelRequested: Boolean = false,
    /** V2-03: optional model override from router policy. */
    val preferredModelId: String? = null,
    val taskType: AiTaskType? = null,
    val assembledContext: com.jarvis.core.llm.AssembledLlmContext? = null,
) {
    init {
        require(requestId.isNotBlank()) { "requestId blank" }
        require(userText.length <= MAX_USER) { "userText too long" }
        require(history.size <= MAX_HISTORY) { "history too long" }
        require(memories.length <= MAX_MEMORIES) { "memories too long" }
    }

    companion object {
        const val MAX_USER = 4_000
        const val MAX_HISTORY = 32
        const val MAX_MEMORIES = 2_000
    }
}

enum class AICompletionStatus {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class AICompletionResult(
    val requestId: String,
    val providerId: String,
    val status: AICompletionStatus,
    val text: String? = null,
    val latencyMs: Long = 0L,
    val error: AIProviderError? = null,
    val streamed: Boolean = false,
    /** V2-03 telemetry */
    val modelId: String? = null,
    val ttftMs: Long? = null,
    /**
     * V2-06: REAL only when streaming first-token was measured.
     * Non-streaming providers must use [TtftKind.NOT_AVAILABLE] and leave [ttftMs] null.
     */
    val ttftKind: com.jarvis.core.runtime.TtftKind =
        if (ttftMs != null) com.jarvis.core.runtime.TtftKind.REAL
        else com.jarvis.core.runtime.TtftKind.NOT_AVAILABLE,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    init {
        require(requestId.isNotBlank())
        require(providerId.isNotBlank())
        when (status) {
            AICompletionStatus.SUCCESS -> require(error == null && !text.isNullOrBlank())
            AICompletionStatus.FAILURE, AICompletionStatus.CANCELLED -> require(error != null)
        }
    }
}
