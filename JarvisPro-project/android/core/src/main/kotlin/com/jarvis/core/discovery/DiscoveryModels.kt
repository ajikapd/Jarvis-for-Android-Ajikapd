package com.jarvis.core.discovery

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolProviderSource

/**
 * Stage 9: контекст discovery. Не передаёт LLM весь Registry.
 */
data class DiscoveryContext(
    val query: String,
    val limit: Int = DEFAULT_LIMIT,
    /** null = не фильтровать по permissions. */
    val grantedPermissions: Set<String>? = null,
    /** null = не фильтровать по online/offline. */
    val isOnline: Boolean? = null,
    /** Максимально допустимый RiskLevel (включительно). */
    val maxRisk: RiskLevel = RiskLevel.SENSITIVE,
    val excludeUnavailable: Boolean = true,
    /** Требовать capabilityId (для capability-centric path). */
    val requireCapability: Boolean = false,
) {
    init {
        require(limit > 0) { "discovery limit must be > 0" }
        require(limit <= HARD_CAP) { "discovery limit exceeds hard cap $HARD_CAP" }
    }

    companion object {
        const val DEFAULT_LIMIT = 6
        /** Жёсткий потолок — нельзя «случайно» отдать весь каталог. */
        const val HARD_CAP = 12
    }
}

/**
 * Stage 9: один релевантный Tool/Capability для Planner (без executable).
 */
data class DiscoveredItem(
    val toolId: String,
    val capabilityId: String?,
    val name: String,
    val description: String,
    val score: Float,
    val riskLevel: RiskLevel,
    val requiredPermissions: List<String>,
    val availability: ToolAvailability,
    val source: ToolProviderSource,
    val locality: ToolExecutionLocality,
    val hasExecutable: Boolean,
    val parameterNames: List<String>,
    val reasons: List<String> = emptyList(),
)

data class DiscoveryResult(
    val query: String,
    val items: List<DiscoveredItem>,
    val truncated: Boolean,
    val considered: Int,
    val notes: List<String> = emptyList(),
) {
    init {
        require(items.size <= DiscoveryContext.HARD_CAP) {
            "DiscoveryResult must not exceed hard cap"
        }
    }
}
