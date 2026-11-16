package com.jarvis.core.remote

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.execution.ToolProviderSource

/**
 * Stage 26: Multi-device / Remote Tools через общий Tool/MCP layer.
 * Не отдельная execution architecture.
 */
enum class RemoteDeviceKind { ANDROID, PC, WEB, OTHER }

enum class RemoteDeviceStatus { ONLINE, OFFLINE, UNKNOWN }

data class RemoteDevice(
    val deviceId: String,
    val kind: RemoteDeviceKind,
    val displayName: String,
    val status: RemoteDeviceStatus = RemoteDeviceStatus.UNKNOWN,
) {
    init {
        require(deviceId.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class RemoteToolRef(
    val deviceId: String,
    val toolId: String,
    val definition: ToolDefinition,
    val source: ToolProviderSource = ToolProviderSource.MCP,
) {
    /** Canonical id in shared registry: remote.{device}.{tool} */
    fun registryId(): String = "remote.$deviceId.$toolId"
}

class RemoteDeviceRegistry {
    private val devices = LinkedHashMap<String, RemoteDevice>()
    private val tools = LinkedHashMap<String, RemoteToolRef>()

    fun registerDevice(device: RemoteDevice) {
        devices[device.deviceId] = device
    }

    fun registerTool(ref: RemoteToolRef) {
        require(devices.containsKey(ref.deviceId)) { "device not registered" }
        tools[ref.registryId()] = ref
    }

    fun devices(): List<RemoteDevice> = devices.values.toList()
    fun onlineDevices(): List<RemoteDevice> =
        devices.values.filter { it.status == RemoteDeviceStatus.ONLINE }

    fun toolsFor(deviceId: String): List<RemoteToolRef> =
        tools.values.filter { it.deviceId == deviceId }

    fun allTools(): List<RemoteToolRef> = tools.values.toList()
    fun findTool(registryId: String): RemoteToolRef? = tools[registryId]
    fun size(): Int = devices.size
}

/**
 * Bridge: remote tools выглядят как обычные ToolDefinition для Planner.
 */
object RemoteToolBridge {
    fun toDefinition(ref: RemoteToolRef): ToolDefinition =
        ref.definition.copy(
            id = ref.registryId(),
            name = "${ref.definition.name} @${ref.deviceId}",
            description = "[${ref.deviceId}] ${ref.definition.description}",
            riskLevel = when {
                ref.definition.riskLevel.ordinal >= RiskLevel.SENSITIVE.ordinal ->
                    ref.definition.riskLevel
                else -> RiskLevel.CONFIRM
            },
        )
}
