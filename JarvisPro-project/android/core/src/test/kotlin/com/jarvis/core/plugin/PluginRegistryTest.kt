package com.jarvis.core.plugin

import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.execution.ToolProviderSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    private fun def(id: String) = ToolDefinition(
        id = id,
        name = id,
        description = "stub $id",
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    @Test
    fun registerStartExposesDefinitions() {
        val plugin = StubPlugin(
            PluginManifest(
                id = "demo.plugin",
                name = "Demo",
                version = "1.0.0",
                capabilities = listOf("demo"),
                permissions = emptyList(),
                toolIds = listOf("demo.echo"),
            ),
            definitions = listOf(def("demo.echo")),
        )
        val reg = PluginRegistry()
        reg.register(plugin)
        assertEquals(1, reg.size())
        assertEquals(PluginLifecycleState.REGISTERED, plugin.state())
        assertTrue(reg.toolProviders().isEmpty())

        assertTrue(plugin.start())
        assertEquals(PluginLifecycleState.STARTED, plugin.state())
        val providers = reg.toolProviders()
        assertEquals(1, providers.size)
        assertEquals(ToolProviderSource.PLUGIN, providers[0].source)
        assertEquals(listOf("demo.echo"), reg.allDefinitions().map { it.id })
    }

    @Test
    fun stopHidesProvider() {
        val plugin = StubPlugin(
            PluginManifest("p", "P", "0.1"),
            definitions = listOf(def("x")),
        )
        val reg = PluginRegistry()
        reg.register(plugin)
        plugin.start()
        assertEquals(1, reg.toolProviders().size)
        plugin.stop()
        assertEquals(PluginLifecycleState.STOPPED, plugin.state())
        assertTrue(reg.toolProviders().isEmpty())
    }

    @Test
    fun failedStartDoesNotExposeTools() {
        val plugin = StubPlugin(
            PluginManifest("bad", "Bad", "1"),
            definitions = listOf(def("y")),
            failOnStart = true,
        )
        val reg = PluginRegistry()
        reg.register(plugin)
        assertFalse(plugin.start())
        assertEquals(PluginLifecycleState.FAILED, plugin.state())
        assertTrue(reg.started().isEmpty())
        assertTrue(reg.allDefinitions().isEmpty())
    }

    @Test
    fun unregisterRemoves() {
        val plugin = StubPlugin(PluginManifest("u", "U", "1"))
        val reg = PluginRegistry()
        reg.register(plugin)
        assertNotNull(reg.unregister("u"))
        assertNull(reg.find("u"))
        assertEquals(0, reg.size())
    }

    @Test
    fun coreDoesNotDependOnConcretePluginTypes() {
        // Registry only holds Plugin interface — StubPlugin is a test double.
        val reg: PluginRegistry = PluginRegistry()
        val asInterface: Plugin = StubPlugin(PluginManifest("i", "I", "1"))
        reg.register(asInterface)
        assertEquals("i", reg.all().single().manifest.id)
    }
}
