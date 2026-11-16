package com.jarvis.core.capability

import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import java.util.UUID

/**
 * Capability — системная возможность для интеллекта.
 * Tool — конкретная реализация под капотом ([backingToolId]).
 *
 * Increment B: слой над существующими tools, без переписывания Voice/Wake.
 */
data class CapabilityDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val operations: List<String> = emptyList(),
    val argumentSchema: List<ToolParameter> = emptyList(),
    val resultHints: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.SAFE,
    val onlineRequirement: OnlineRequirement = OnlineRequirement.LOCAL_ONLY,
    /** Доступность: всегда / зависит от пермишена / от сети. */
    val availability: CapabilityAvailability = CapabilityAvailability.ALWAYS,
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.AUTO,
    /** Стабильный id tool-реализации (flashlight, phone.call, …). */
    val backingToolId: String,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
)

enum class CapabilityAvailability {
    ALWAYS,
    NEEDS_PERMISSION,
    NEEDS_ONLINE,
    NEEDS_ACCESSIBILITY,
    CONDITIONAL,
}

enum class ConfirmationPolicy {
    /** Выполнять сразу при SAFE/LOW. */
    AUTO,
    /** Всегда спрашивать. */
    ALWAYS_CONFIRM,
    /** По RiskLevel из descriptor / policy. */
    BY_RISK,
}

data class CapabilityCall(
    val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val capabilityId: String,
    val operation: String? = null,
    val arguments: Map<String, Any> = emptyMap(),
)

data class CapabilityResult(
    val requestId: String,
    val capabilityId: String,
    val status: ToolResultStatus,
    val message: String? = null,
    val data: Map<String, Any> = emptyMap(),
    val durationMs: Long = 0L,
    val verified: Boolean = false,
    val verificationNotes: List<String> = emptyList(),
    val toolResult: ToolResult? = null,
)

/** Краткое описание для LLM / discovery (без executor). */
data class CapabilitySummary(
    val id: String,
    val title: String,
    val description: String,
    val operations: List<String>,
    val riskLevel: RiskLevel,
    val examples: List<String>,
    val backingToolId: String,
)
