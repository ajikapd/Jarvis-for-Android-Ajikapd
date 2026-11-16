package com.jarvis.core.plugin

import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.execution.ToolProviderSource

/**
 * Stage 15: манифест плагина.
 * Core не зависит от конкретных plugin implementations.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val configuration: Map<String, String> = emptyMap(),
    val toolIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "plugin id blank" }
        require(name.isNotBlank()) { "plugin name blank" }
        require(version.isNotBlank()) { "plugin version blank" }
        require(version.length <= 32) { "version too long" }
        require(capabilities.size <= 32)
        require(permissions.size <= 32)
        require(configuration.size <= 32)
        require(toolIds.size <= 64)
        require(description.length <= 500)
    }
}

enum class PluginLifecycleState {
    REGISTERED,
    STARTED,
    STOPPED,
    FAILED,
}

/**
 * Stage 15: источник ToolDefinition (Native / Plugin / MCP — единый вид для Planner).
 */
interface ToolProvider {
    val providerId: String
    val source: ToolProviderSource
    fun definitions(): List<ToolDefinition>
}

/**
 * Stage 15: Plugin с lifecycle. Не Executor.
 */
interface Plugin {
    val manifest: PluginManifest
    fun state(): PluginLifecycleState
    fun start(): Boolean
    fun stop(): Boolean
    fun toolProvider(): ToolProvider?
}

/**
 * Реестр плагинов. Core хранит только интерфейсы.
 */
class PluginRegistry {
    private val byId = LinkedHashMap<String, Plugin>()

    fun register(plugin: Plugin) {
        require(plugin.manifest.id.isNotBlank())
        byId[plugin.manifest.id] = plugin
    }

    fun unregister(id: String): Plugin? = byId.remove(id)

    fun find(id: String): Plugin? = byId[id]

    fun all(): List<Plugin> = byId.values.toList()

    fun started(): List<Plugin> =
        byId.values.filter { it.state() == PluginLifecycleState.STARTED }

    fun toolProviders(): List<ToolProvider> =
        started().mapNotNull { it.toolProvider() }

    fun allDefinitions(): List<ToolDefinition> =
        toolProviders().flatMap { it.definitions() }

    fun size(): Int = byId.size
}

/**
 * Тестовый / stub plugin без Android зависимостей.
 */
class StubPlugin(
    override val manifest: PluginManifest,
    private val definitions: List<ToolDefinition> = emptyList(),
    private val failOnStart: Boolean = false,
) : Plugin {
    private var state: PluginLifecycleState = PluginLifecycleState.REGISTERED

    override fun state(): PluginLifecycleState = state

    override fun start(): Boolean {
        if (failOnStart) {
            state = PluginLifecycleState.FAILED
            return false
        }
        state = PluginLifecycleState.STARTED
        return true
    }

    override fun stop(): Boolean {
        state = PluginLifecycleState.STOPPED
        return true
    }

    override fun toolProvider(): ToolProvider? {
        if (state != PluginLifecycleState.STARTED) return null
        if (definitions.isEmpty()) return null
        return object : ToolProvider {
            override val providerId: String = manifest.id
            override val source: ToolProviderSource = ToolProviderSource.PLUGIN
            override fun definitions(): List<ToolDefinition> = definitions
        }
    }
}
