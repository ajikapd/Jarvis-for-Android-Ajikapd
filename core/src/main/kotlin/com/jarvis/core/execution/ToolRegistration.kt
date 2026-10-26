package com.jarvis.core.execution

import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.common.model.ToolDefinition

/**
 * Stage 8F: запись реестра — **Definition + catalog metadata**.
 * Executable Tool хранится отдельно ([CatalogToolRegistry.bindExecutable]).
 * Capability связывается через [capabilityId], не смешиваясь с execution.
 */
data class ToolRegistration(
    val definition: ToolDefinition,
    val capabilityId: String? = null,
    val source: ToolProviderSource = ToolProviderSource.NATIVE,
    val locality: ToolExecutionLocality = ToolExecutionLocality.LOCAL,
    val availability: ToolAvailability = ToolAvailability.ALWAYS,
    val metadata: ToolMetadata = ToolMetadata(),
    /** Опциональная подсказка категории для autonomy pipeline (не risk decision). */
    val actionKindHint: ActionKind? = null,
) {
    val toolId: String get() = definition.id

    init {
        capabilityId?.let {
            require(it.isNotBlank()) { "capabilityId blank" }
            require(it.length <= MAX_CAPABILITY_ID) { "capabilityId too long" }
        }
    }

    companion object {
        const val MAX_CAPABILITY_ID = 96
    }
}

/**
 * Stage 8F: снимок записи для диагностики / discovery (Stage 9).
 * Не содержит executable и не разрешает выполнение.
 */
data class ToolCatalogEntry(
    val toolId: String,
    val name: String,
    val description: String,
    val capabilityId: String?,
    val source: ToolProviderSource,
    val locality: ToolExecutionLocality,
    val availability: ToolAvailability,
    val riskLevel: com.jarvis.core.common.model.RiskLevel,
    val requiredPermissions: List<String>,
    val hasExecutable: Boolean,
    val actionKindHint: ActionKind?,
    val metadata: ToolMetadata,
    val parameterNames: List<String>,
    val contractVersion: String,
)
