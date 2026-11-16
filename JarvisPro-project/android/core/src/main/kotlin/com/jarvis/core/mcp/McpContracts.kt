package com.jarvis.core.mcp

import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.execution.CatalogToolRegistry
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolMetadata
import com.jarvis.core.execution.ToolProviderSource
import com.jarvis.core.execution.ToolRegistration
import com.jarvis.core.plugin.ToolProvider

/**
 * Stage 16: декларативное описание MCP tool (ещё не network client).
 * Для Planner выглядит как обычный ToolDefinition.
 */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputParameterNames: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.CONFIRM,
    val requiredPermissions: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank())
        require(name.length <= 64)
        require(description.length <= 500)
        require(inputParameterNames.size <= 16)
    }

    /** Стабильный toolId в общем реестре. */
    fun toolId(serverId: String): String = "mcp.$serverId.$name"
}

data class McpServerDescriptor(
    val serverId: String,
    val displayName: String,
    val endpointConfigured: Boolean = false,
    val tools: List<McpToolDescriptor> = emptyList(),
) {
    init {
        require(serverId.isNotBlank() && serverId.length <= 64)
        require(displayName.isNotBlank())
        require(tools.size <= 64)
    }
}

/**
 * Stage 16: MCP → [ToolProvider] (source=MCP).
 * **Не** отдельный executor — definitions попадают в тот же CatalogToolRegistry.
 * Реальный MCP network transport на этом этапе не обязателен (stub OK).
 */
class McpToolProvider(
    private val server: McpServerDescriptor,
) : ToolProvider {
    override val providerId: String = "mcp.${server.serverId}"
    override val source: ToolProviderSource = ToolProviderSource.MCP

    override fun definitions(): List<ToolDefinition> =
        server.tools.map { tool ->
            ToolDefinition(
                id = tool.toolId(server.serverId),
                name = tool.name,
                description = tool.description,
                parameters = tool.inputParameterNames.map { n ->
                    ToolParameter(name = n, description = n, required = false)
                },
                riskLevel = tool.riskLevel,
                onlineRequirement = OnlineRequirement.ONLINE_REQUIRED,
                requiredPermissions = tool.requiredPermissions,
            )
        }

    fun isAvailable(): Boolean = server.endpointConfigured

    /**
     * Импорт definitions в [CatalogToolRegistry] как обычные tools (source=MCP).
     * Executable не регистрируется — без отдельной MCP execution architecture.
     */
    fun importDefinitions(catalog: CatalogToolRegistry): Int {
        var n = 0
        for (def in definitions()) {
            val result = catalog.register(
                ToolRegistration(
                    definition = def,
                    source = ToolProviderSource.MCP,
                    locality = ToolExecutionLocality.REMOTE,
                    availability = if (server.endpointConfigured) {
                        ToolAvailability.NEEDS_ONLINE
                    } else {
                        ToolAvailability.UNAVAILABLE
                    },
                    metadata = ToolMetadata(
                        tags = setOf("mcp", "stub"),
                        source = "mcp",
                        extras = mapOf("mcp_server" to server.serverId),
                    ),
                ),
            )
            if (result.isOk) n++
        }
        return n
    }
}

/**
 * Реестр MCP-серверов (stub). Не сеть.
 */
class McpRegistry {
    private val byId = LinkedHashMap<String, McpToolProvider>()

    fun register(server: McpServerDescriptor): McpToolProvider {
        val provider = McpToolProvider(server)
        byId[server.serverId] = provider
        return provider
    }

    fun unregister(serverId: String): McpToolProvider? = byId.remove(serverId)

    fun find(serverId: String): McpToolProvider? = byId[serverId]

    fun all(): List<McpToolProvider> = byId.values.toList()

    fun providers(): List<ToolProvider> = byId.values.toList()

    fun importAllDefinitions(catalog: CatalogToolRegistry): Int =
        byId.values.sumOf { it.importDefinitions(catalog) }

    fun size(): Int = byId.size
}
