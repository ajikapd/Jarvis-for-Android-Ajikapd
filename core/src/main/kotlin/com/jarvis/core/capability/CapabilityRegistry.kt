package com.jarvis.core.capability

/**
 * Единый реестр возможностей. Наполняется из существующих tools при старте.
 */
class CapabilityRegistry {
    private val byId = LinkedHashMap<String, CapabilityDescriptor>()
    private val byToolId = LinkedHashMap<String, String>()

    fun register(descriptor: CapabilityDescriptor) {
        byId[descriptor.id] = descriptor
        byToolId[descriptor.backingToolId] = descriptor.id
    }

    fun unregister(id: String) {
        val d = byId.remove(id) ?: return
        byToolId.remove(d.backingToolId)
    }

    fun find(id: String): CapabilityDescriptor? = byId[id]

    fun findByToolId(toolId: String): CapabilityDescriptor? =
        byToolId[toolId]?.let { byId[it] }

    fun all(): List<CapabilityDescriptor> = byId.values.toList()

    fun size(): Int = byId.size

    fun summaries(): List<CapabilitySummary> = all().map { it.toSummary() }
}

fun CapabilityDescriptor.toSummary(): CapabilitySummary = CapabilitySummary(
    id = id,
    title = title,
    description = description,
    operations = operations,
    riskLevel = riskLevel,
    examples = examples,
    backingToolId = backingToolId,
)
