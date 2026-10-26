package com.jarvis.core.capability

import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityLayerTest {

    private class FakeFlashlight : Tool {
        override val definition = ToolDefinition(
            id = "flashlight",
            name = "Фонарик",
            description = "Фонарик",
            parameters = listOf(
                ToolParameter("action", "on/off", ParameterType.STRING, required = true),
            ),
            riskLevel = RiskLevel.SAFE,
        )

        override fun execute(call: ToolCall): ToolResult =
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "Включён.")
    }

    private class FakeSms : Tool {
        override val definition = ToolDefinition(
            id = "sms",
            name = "SMS",
            description = "SMS",
            riskLevel = RiskLevel.SENSITIVE,
        )

        override fun execute(call: ToolCall): ToolResult =
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, "ok")
    }

    private fun registryWith(vararg tools: Tool): CapabilityRegistry {
        val tr = ToolRegistry()
        tools.forEach { tr.register(it) }
        return ToolCapabilityCatalog.buildFromTools(tr)
    }

    @Test
    fun mapsFlashlightToDeviceLight() {
        val reg = registryWith(FakeFlashlight())
        val cap = reg.find("DEVICE_LIGHT")
        assertNotNull(cap)
        assertEquals("flashlight", cap!!.backingToolId)
        assertEquals("DEVICE_LIGHT", reg.findByToolId("flashlight")!!.id)
    }

    @Test
    fun discoveryPrefersFlashlightForLightQuery() {
        val reg = registryWith(FakeFlashlight(), FakeSms())
        val d = CapabilityDiscovery(reg)
        val top = d.discover("включи фонарик", limit = 3)
        assertTrue(top.isNotEmpty())
        assertEquals("DEVICE_LIGHT", top.first().id)
    }

    @Test
    fun discoveryDoesNotDumpEntireCatalog() {
        val tr = ToolRegistry()
        ToolCapabilityCatalog.knownToolIds().forEach { id ->
            tr.register(object : Tool {
                override val definition = ToolDefinition(id = id, name = id, description = id)
                override fun execute(call: ToolCall) =
                    ToolResult(call.requestId, id, ToolResultStatus.SUCCESS, "ok")
            })
        }
        val reg = ToolCapabilityCatalog.buildFromTools(tr)
        assertTrue(reg.size() >= 10)
        val subset = CapabilityDiscovery(reg, maxResults = 5).discover("какая погода в Москве")
        assertTrue(subset.size <= 5)
        assertTrue(subset.any { it.id == "WEATHER_LOOKUP" })
    }

    @Test
    fun executorAndVerifierMarkSuccess() {
        val tools = ToolRegistry().also { it.register(FakeFlashlight()) }
        val caps = ToolCapabilityCatalog.buildFromTools(tools)
        val exec = CapabilityExecutor(caps, DefaultToolExecutor(tools))
        val result = exec.execute(
            CapabilityCall(
                requestId = "r1",
                capabilityId = "DEVICE_LIGHT",
                operation = "on",
                arguments = mapOf("action" to "on"),
            ),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.verified)
        assertEquals("DEVICE_LIGHT", result.capabilityId)
        assertNotNull(result.toolResult)
    }

    @Test
    fun awareExecutorAttachesCapabilityMetadata() {
        val tools = ToolRegistry().also { it.register(FakeFlashlight()) }
        val caps = ToolCapabilityCatalog.buildFromTools(tools)
        val aware = CapabilityAwareToolExecutor(DefaultToolExecutor(tools), caps)
        val result = aware.execute(
            ToolCall(requestId = "r2", toolId = "flashlight", arguments = mapOf("action" to "on")),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("DEVICE_LIGHT", result.data["capabilityId"])
        assertEquals(true, result.data["verified"])
    }

    @Test
    fun sensitiveToolGetsAlwaysConfirmPolicy() {
        val reg = registryWith(FakeSms())
        val cap = reg.find("COMMUNICATION_SMS")
        assertNotNull(cap)
        assertEquals(ConfirmationPolicy.ALWAYS_CONFIRM, cap!!.confirmationPolicy)
    }

    @Test
    fun unknownCapabilityFailsWithoutCallingTool() {
        val tools = ToolRegistry().also { it.register(FakeFlashlight()) }
        val caps = ToolCapabilityCatalog.buildFromTools(tools)
        val exec = CapabilityExecutor(caps, DefaultToolExecutor(tools))
        val result = exec.execute(
            CapabilityCall(requestId = "r3", capabilityId = "NO_SUCH_CAP"),
        )
        assertEquals(ToolResultStatus.FAILED, result.status)
        assertFalse(result.verified)
    }
}
