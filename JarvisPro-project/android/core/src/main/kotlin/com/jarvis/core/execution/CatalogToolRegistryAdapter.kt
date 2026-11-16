package com.jarvis.core.execution

import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.capability.CapabilityAvailability
import com.jarvis.core.capability.CapabilityDescriptor
import com.jarvis.core.capability.CapabilityRegistry
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.ToolDefinition

/**
 * Stage 8F: адаптер legacy ToolRegistry / CapabilityRegistry → [CatalogToolRegistry].
 * Не исполняет tools. Не делает Discovery (Stage 9).
 */
object CatalogToolRegistryAdapter {

    fun importDefinitionsFromCapabilityRegistry(
        catalog: CatalogToolRegistry,
        capabilities: CapabilityRegistry,
        source: ToolProviderSource = ToolProviderSource.NATIVE,
    ): Int {
        var n = 0
        for (cap in capabilities.all()) {
            val def = definitionFromCapability(cap)
            val result = catalog.register(
                ToolRegistration(
                    definition = def,
                    capabilityId = cap.id,
                    source = source,
                    locality = localityFrom(cap),
                    availability = mapAvailability(cap.availability),
                    metadata = ToolMetadata(
                        tags = cap.tags.toSet(),
                        source = source.name.lowercase(),
                        notes = listOf("from_capability=${cap.id}"),
                    ),
                    actionKindHint = null,
                ),
            )
            if (result.isOk) n++
        }
        return n
    }

    /**
     * Импорт executable из legacy registry: если definition уже в catalog —
     * bind; иначе register definition + bind.
     */
    fun importExecutablesFromToolRegistry(
        catalog: CatalogToolRegistry,
        tools: ToolRegistry,
        source: ToolProviderSource = ToolProviderSource.NATIVE,
        actionKindHint: ((ToolDefinition) -> ActionKind?)? = null,
    ): Int {
        var n = 0
        for (tool in tools.available()) {
            val def = tool.definition
            if (catalog.findDefinition(def.id) == null) {
                catalog.register(
                    ToolRegistration(
                        definition = def,
                        source = source,
                        locality = ToolExecutionLocality.LOCAL,
                        availability = ToolAvailability.ALWAYS,
                        metadata = ToolMetadata(source = source.name.lowercase()),
                        actionKindHint = actionKindHint?.invoke(def),
                    ),
                )
            }
            val bind = catalog.bindExecutable(tool)
            if (bind.isOk) n++
        }
        return n
    }

    fun definitionFromCapability(cap: CapabilityDescriptor): ToolDefinition =
        ToolDefinition(
            id = cap.backingToolId,
            name = cap.title,
            description = cap.description,
            parameters = cap.argumentSchema,
            riskLevel = cap.riskLevel,
            requiredPermissions = cap.permissions,
            onlineRequirement = cap.onlineRequirement,
        )

    fun mapAvailability(a: CapabilityAvailability): ToolAvailability = when (a) {
        CapabilityAvailability.ALWAYS -> ToolAvailability.ALWAYS
        CapabilityAvailability.NEEDS_PERMISSION -> ToolAvailability.NEEDS_PERMISSION
        CapabilityAvailability.NEEDS_ONLINE -> ToolAvailability.NEEDS_ONLINE
        CapabilityAvailability.NEEDS_ACCESSIBILITY -> ToolAvailability.NEEDS_ACCESSIBILITY
        CapabilityAvailability.CONDITIONAL -> ToolAvailability.CONDITIONAL
    }

    private fun localityFrom(cap: CapabilityDescriptor): ToolExecutionLocality =
        when (cap.onlineRequirement) {
            com.jarvis.core.common.model.OnlineRequirement.LOCAL_ONLY ->
                ToolExecutionLocality.LOCAL
            com.jarvis.core.common.model.OnlineRequirement.ONLINE_REQUIRED ->
                ToolExecutionLocality.REMOTE
            else -> ToolExecutionLocality.HYBRID
        }
}
