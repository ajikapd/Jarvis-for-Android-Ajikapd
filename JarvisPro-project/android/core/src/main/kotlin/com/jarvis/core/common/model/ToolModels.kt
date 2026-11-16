package com.jarvis.core.common.model

import java.util.UUID

/**
 * Модели инструментов (tools/skills).
 * Контракты живут в core/common (раздел 12 AGENT_START), конкретные
 * инструменты — в модуле :tools.
 */

enum class RiskLevel { SAFE, LOW, CONFIRM, SENSITIVE, BLOCKED }

enum class OnlineRequirement { LOCAL_ONLY, LOCAL_PREFERRED, ONLINE_ALLOWED, ONLINE_REQUIRED }

enum class ParameterType { STRING, INTEGER, BOOLEAN, ENUM }

data class ToolParameter(
    val name: String,
    val description: String,
    val type: ParameterType = ParameterType.STRING,
    val required: Boolean = false,
    val allowedValues: List<String> = emptyList()
)

data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.SAFE,
    val requiredPermissions: List<String> = emptyList(),
    val onlineRequirement: OnlineRequirement = OnlineRequirement.LOCAL_ONLY,
    /** Stage 8E: версия execution-contract (не меняет risk/autonomy). */
    val contractVersion: String = CONTRACT_VERSION_8E,
) {
    init {
        require(id.isNotBlank()) { "ToolDefinition.id blank" }
        require(name.isNotBlank()) { "ToolDefinition.name blank" }
        require(contractVersion.isNotBlank()) { "ToolDefinition.contractVersion blank" }
    }

    companion object {
        const val CONTRACT_VERSION_8E: String = "8E.1"
    }
}

data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val toolId: String,
    val arguments: Map<String, Any> = emptyMap()
)

enum class ToolResultStatus {
    SUCCESS,
    FAILED,
    DENIED,
    NEEDS_CONFIRMATION,
    UNAVAILABLE,
    OFFLINE,
    AMBIGUOUS
}

data class ToolResult(
    val requestId: String,
    val toolId: String,
    val status: ToolResultStatus,
    val message: String? = null,
    val data: Map<String, Any> = emptyMap(),
    val durationMs: Long = 0L
)

enum class AssistantResponseStatus {
    PROCESSED,
    NEEDS_CONFIRMATION,
    NEEDS_CLARIFICATION,
    DENIED,
    FAILED
}

data class AssistantResponse(
    val requestId: String,
    val replyText: String,
    val status: AssistantResponseStatus,
    val intent: Intent? = null,
    val toolResult: ToolResult? = null,
    val durationMs: Long = 0L,
    /** V2-01 route telemetry (nullable for backward compatible tests). */
    val route: String? = null,
    val routeReason: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val fallbackUsed: Boolean = false,
    val verification: String? = null,
)