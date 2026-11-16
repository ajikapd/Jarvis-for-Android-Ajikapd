package com.jarvis.core.execution

import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.ToolDefinition

/**
 * Stage 8F: единый каталог Tool.
 *
 * Разделяет:
 * - [ToolDefinition] / [ToolRegistration] — декларация
 * - [Tool] executable — опциональная привязка реализации
 * - capabilityId — связь с Capability слоем
 *
 * Не выполняет действий. Не заменяет legacy [com.jarvis.core.common.ToolRegistry]
 * в BrainGraph на этом этапе — сосуществует как контракт 8F.
 * Не реализует Stage 9 Discovery.
 */
class CatalogToolRegistry {

    private val registrations = LinkedHashMap<String, ToolRegistration>()
    private val executables = LinkedHashMap<String, Tool>()
    private val byCapability = LinkedHashMap<String, MutableSet<String>>()

    fun size(): Int = registrations.size

    fun executableCount(): Int = executables.size

    /**
     * Регистрация definition-записи. Executable не требуется.
     * Конфликт id с другим definition → fail-closed reject.
     */
    fun register(registration: ToolRegistration): ToolRegistryResult {
        val id = registration.toolId
        val existing = registrations[id]
        if (existing != null && existing.definition != registration.definition) {
            return ToolRegistryResult.Rejected(
                ToolRegistryRejectReason.DEFINITION_CONFLICT,
                "Tool id=$id already registered with different ToolDefinition",
            )
        }
        if (existing != null &&
            existing.capabilityId != null &&
            registration.capabilityId != null &&
            existing.capabilityId != registration.capabilityId
        ) {
            return ToolRegistryResult.Rejected(
                ToolRegistryRejectReason.CAPABILITY_CONFLICT,
                "Tool id=$id capability conflict: ${existing.capabilityId} vs ${registration.capabilityId}",
            )
        }

        // Unlink old capability index if replacing same definition with updated metadata
        existing?.capabilityId?.let { removeCapabilityIndex(it, id) }

        registrations[id] = registration
        registration.capabilityId?.let { addCapabilityIndex(it, id) }
        return ToolRegistryResult.Ok(id)
    }

    /**
     * Привязка executable. Definition должна уже быть зарегистрирована.
     * executable.definition.id обязан совпадать; definition должен совпадать с catalog.
     */
    fun bindExecutable(executable: Tool): ToolRegistryResult {
        val id = executable.definition.id
        val reg = registrations[id]
            ?: return ToolRegistryResult.Rejected(
                ToolRegistryRejectReason.DEFINITION_MISSING,
                "Cannot bind executable: definition not registered for id=$id",
            )
        if (executable.definition != reg.definition) {
            return ToolRegistryResult.Rejected(
                ToolRegistryRejectReason.EXECUTABLE_DEFINITION_MISMATCH,
                "Executable ToolDefinition differs from registered definition for id=$id",
            )
        }
        executables[id] = executable
        return ToolRegistryResult.Ok(id)
    }

    fun unbindExecutable(toolId: String): Boolean = executables.remove(toolId) != null

    fun unregister(toolId: String): Boolean {
        val reg = registrations.remove(toolId) ?: return false
        executables.remove(toolId)
        reg.capabilityId?.let { removeCapabilityIndex(it, toolId) }
        return true
    }

    fun linkCapability(toolId: String, capabilityId: String): ToolRegistryResult {
        val reg = registrations[toolId]
            ?: return ToolRegistryResult.Rejected(
                ToolRegistryRejectReason.DEFINITION_MISSING,
                "Tool not registered: $toolId",
            )
        require(capabilityId.isNotBlank())
        reg.capabilityId?.let { removeCapabilityIndex(it, toolId) }
        registrations[toolId] = reg.copy(capabilityId = capabilityId)
        addCapabilityIndex(capabilityId, toolId)
        return ToolRegistryResult.Ok(toolId)
    }

    fun findRegistration(toolId: String): ToolRegistration? = registrations[toolId]

    fun findDefinition(toolId: String): ToolDefinition? = registrations[toolId]?.definition

    /** null если только definition без executable — разделение Definition / Executable. */
    fun findExecutable(toolId: String): Tool? = executables[toolId]

    fun hasExecutable(toolId: String): Boolean = executables.containsKey(toolId)

    fun findByCapability(capabilityId: String): List<ToolRegistration> =
        byCapability[capabilityId].orEmpty().mapNotNull { registrations[it] }

    fun allRegistrations(): List<ToolRegistration> = registrations.values.toList()

    fun allDefinitions(): List<ToolDefinition> = registrations.values.map { it.definition }

    fun definitionOnlyIds(): List<String> =
        registrations.keys.filter { it !in executables }

    fun executableIds(): List<String> = executables.keys.toList()

    fun catalogEntries(): List<ToolCatalogEntry> = registrations.values.map { reg ->
        ToolCatalogEntry(
            toolId = reg.toolId,
            name = reg.definition.name,
            description = reg.definition.description,
            capabilityId = reg.capabilityId,
            source = reg.source,
            locality = reg.locality,
            availability = reg.availability,
            riskLevel = reg.definition.riskLevel,
            requiredPermissions = reg.definition.requiredPermissions,
            hasExecutable = hasExecutable(reg.toolId),
            actionKindHint = reg.actionKindHint,
            metadata = reg.metadata,
            parameterNames = reg.definition.parameters.map { it.name },
            contractVersion = reg.definition.contractVersion,
        )
    }

    /**
     * Резолв definition для Stage 8E [ToolExecutionRequestFactory].
     * Не проверяет Gate/Policy и не исполняет.
     */
    fun resolveDefinitionForExecution(toolId: String): ToolDefinition? =
        findDefinition(toolId)

    private fun addCapabilityIndex(capabilityId: String, toolId: String) {
        byCapability.getOrPut(capabilityId) { linkedSetOf() }.add(toolId)
    }

    private fun removeCapabilityIndex(capabilityId: String, toolId: String) {
        val set = byCapability[capabilityId] ?: return
        set.remove(toolId)
        if (set.isEmpty()) byCapability.remove(capabilityId)
    }
}

enum class ToolRegistryRejectReason {
    DEFINITION_CONFLICT,
    CAPABILITY_CONFLICT,
    DEFINITION_MISSING,
    EXECUTABLE_DEFINITION_MISMATCH,
}

sealed class ToolRegistryResult {
    data class Ok(val toolId: String) : ToolRegistryResult()
    data class Rejected(val reason: ToolRegistryRejectReason, val message: String) : ToolRegistryResult()

    val isOk: Boolean get() = this is Ok
}
