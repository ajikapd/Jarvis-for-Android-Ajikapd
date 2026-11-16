package com.jarvis.core.mcp

import com.jarvis.core.discovery.CatalogToolDiscovery
import com.jarvis.core.discovery.DiscoveryContext
import com.jarvis.core.execution.CatalogToolRegistry
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolProviderSource
import com.jarvis.core.planner.DeterministicAIPlanner
import com.jarvis.core.planner.PlannerContext
import com.jarvis.core.planner.UserGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRegistryTest {

    private fun sampleServer(configured: Boolean = false) = McpServerDescriptor(
        serverId = "filesystem",
        displayName = "Filesystem MCP",
        endpointConfigured = configured,
        tools = listOf(
            McpToolDescriptor(
                name = "list_dir",
                description = "List directory",
                inputParameterNames = listOf("path"),
            ),
            McpToolDescriptor(
                name = "read_file",
                description = "Read a file",
                inputParameterNames = listOf("path"),
            ),
        ),
    )

    @Test
    fun providerExposesMcpSourceDefinitions() {
        val provider = McpToolProvider(sampleServer())
        assertEquals(ToolProviderSource.MCP, provider.source)
        assertEquals(2, provider.definitions().size)
        assertTrue(provider.definitions().all { it.id.startsWith("mcp.filesystem.") })
        assertFalse(provider.isAvailable())
    }

    @Test
    fun importIntoCatalogLooksLikeNormalTools() {
        val catalog = CatalogToolRegistry()
        val mcp = McpRegistry()
        mcp.register(sampleServer(configured = true))
        assertEquals(2, mcp.importAllDefinitions(catalog))
        assertEquals(2, catalog.size())
        val entry = catalog.catalogEntries().first { it.toolId == "mcp.filesystem.list_dir" }
        assertEquals(ToolProviderSource.MCP, entry.source)
        assertEquals(ToolAvailability.NEEDS_ONLINE, entry.availability)
        assertFalse(entry.hasExecutable)
    }

    @Test
    fun discoveryAndPlannerTreatMcpAsTools() {
        val catalog = CatalogToolRegistry()
        McpRegistry().apply {
            register(sampleServer(configured = true))
            importAllDefinitions(catalog)
        }
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover(
            DiscoveryContext(query = "list directory files", isOnline = true, limit = 6),
        )
        assertTrue(result.items.any { it.toolId.startsWith("mcp.") })
        val plan = DeterministicAIPlanner().plan(
            UserGoal("g1", "list directory"),
            PlannerContext(discovery = result),
        )
        assertTrue(plan.steps.any { it.toolId?.startsWith("mcp.") == true })
    }

    @Test
    fun unconfiguredMarkedUnavailable() {
        val catalog = CatalogToolRegistry()
        val mcp = McpRegistry()
        mcp.register(sampleServer(configured = false))
        mcp.importAllDefinitions(catalog)
        val entry = catalog.catalogEntries().single { it.toolId.endsWith("list_dir") }
        assertEquals(ToolAvailability.UNAVAILABLE, entry.availability)
    }

    @Test
    fun unregisterRemovesProvider() {
        val mcp = McpRegistry()
        mcp.register(sampleServer())
        assertEquals(1, mcp.size())
        mcp.unregister("filesystem")
        assertEquals(0, mcp.size())
    }
}
