package com.jarvis.core.execution

import com.jarvis.core.autonomy.ActionIntegrity
import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.autonomy.ActionRiskAssessment
import com.jarvis.core.autonomy.ActionRiskClass
import com.jarvis.core.autonomy.ActionRiskReasonCode
import com.jarvis.core.autonomy.ActionSafetyGateResult
import com.jarvis.core.autonomy.PolicyDecisionBinding
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.capability.CapabilityAvailability
import com.jarvis.core.capability.CapabilityDescriptor
import com.jarvis.core.capability.CapabilityRegistry
import com.jarvis.core.common.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogToolRegistryTest {

    private fun flashlightDef() = ToolDefinition(
        id = "flashlight",
        name = "Фонарик",
        description = "Torch",
        parameters = listOf(
            ToolParameter("action", "on|off", ParameterType.ENUM, true, listOf("on", "off")),
        ),
        riskLevel = RiskLevel.SAFE,
    )

    private fun stubTool(def: ToolDefinition): Tool = object : Tool {
        override val definition = def
        override fun execute(call: ToolCall): ToolResult =
            ToolResult(call.requestId, def.id, ToolResultStatus.SUCCESS, "ok")
    }

    @Test
    fun registerDefinitionWithoutExecutable() {
        val reg = CatalogToolRegistry()
        val r = reg.register(
            ToolRegistration(
                definition = flashlightDef(),
                capabilityId = "DEVICE_LIGHT",
                source = ToolProviderSource.NATIVE,
                locality = ToolExecutionLocality.LOCAL,
                availability = ToolAvailability.ALWAYS,
                actionKindHint = ActionKind.LOCAL_DEVICE_SAFE,
            ),
        )
        assertTrue(r.isOk)
        assertEquals(1, reg.size())
        assertEquals(0, reg.executableCount())
        assertNotNull(reg.findDefinition("flashlight"))
        assertNull(reg.findExecutable("flashlight"))
        assertEquals(listOf("flashlight"), reg.definitionOnlyIds())
        assertTrue(reg.hasExecutable("flashlight").not())
    }

    @Test
    fun bindExecutableSeparatesFromDefinition() {
        val catalog = CatalogToolRegistry()
        catalog.register(ToolRegistration(definition = flashlightDef()))
        val bind = catalog.bindExecutable(stubTool(flashlightDef()))
        assertTrue(bind.isOk)
        assertEquals(1, catalog.executableCount())
        assertNotNull(catalog.findExecutable("flashlight"))
        assertTrue(catalog.definitionOnlyIds().isEmpty())
        assertEquals(listOf("flashlight"), catalog.executableIds())
    }

    @Test
    fun bindExecutableRequiresDefinition() {
        val catalog = CatalogToolRegistry()
        val r = catalog.bindExecutable(stubTool(flashlightDef()))
        assertFalse(r.isOk)
        assertEquals(
            ToolRegistryRejectReason.DEFINITION_MISSING,
            (r as ToolRegistryResult.Rejected).reason,
        )
    }

    @Test
    fun bindExecutableRejectsMismatchedDefinition() {
        val catalog = CatalogToolRegistry()
        catalog.register(ToolRegistration(definition = flashlightDef()))
        val other = flashlightDef().copy(description = "CHANGED")
        val r = catalog.bindExecutable(stubTool(other))
        assertEquals(
            ToolRegistryRejectReason.EXECUTABLE_DEFINITION_MISMATCH,
            (r as ToolRegistryResult.Rejected).reason,
        )
    }

    @Test
    fun definitionConflictRejected() {
        val catalog = CatalogToolRegistry()
        catalog.register(ToolRegistration(definition = flashlightDef()))
        val r = catalog.register(
            ToolRegistration(definition = flashlightDef().copy(name = "Other")),
        )
        assertEquals(
            ToolRegistryRejectReason.DEFINITION_CONFLICT,
            (r as ToolRegistryResult.Rejected).reason,
        )
    }

    @Test
    fun findByCapability() {
        val catalog = CatalogToolRegistry()
        catalog.register(
            ToolRegistration(
                definition = flashlightDef(),
                capabilityId = "DEVICE_LIGHT",
            ),
        )
        val found = catalog.findByCapability("DEVICE_LIGHT")
        assertEquals(1, found.size)
        assertEquals("flashlight", found.first().toolId)
    }

    @Test
    fun linkCapabilityUpdatesIndex() {
        val catalog = CatalogToolRegistry()
        catalog.register(ToolRegistration(definition = flashlightDef()))
        catalog.linkCapability("flashlight", "DEVICE_LIGHT")
        assertEquals("DEVICE_LIGHT", catalog.findRegistration("flashlight")!!.capabilityId)
        assertEquals(1, catalog.findByCapability("DEVICE_LIGHT").size)
    }

    @Test
    fun catalogEntryReflectsHasExecutableAndMetadata() {
        val catalog = CatalogToolRegistry()
        catalog.register(
            ToolRegistration(
                definition = flashlightDef(),
                source = ToolProviderSource.PLUGIN,
                locality = ToolExecutionLocality.HYBRID,
                availability = ToolAvailability.NEEDS_PERMISSION,
                metadata = ToolMetadata(tags = setOf("light")),
            ),
        )
        val entry = catalog.catalogEntries().single()
        assertFalse(entry.hasExecutable)
        assertEquals(ToolProviderSource.PLUGIN, entry.source)
        assertEquals(ToolExecutionLocality.HYBRID, entry.locality)
        assertEquals(ToolAvailability.NEEDS_PERMISSION, entry.availability)
        assertTrue(entry.metadata.tags.contains("light"))

        catalog.bindExecutable(stubTool(flashlightDef()))
        assertTrue(catalog.catalogEntries().single().hasExecutable)
    }

    @Test
    fun sourcesNativePluginMcpSupported() {
        val catalog = CatalogToolRegistry()
        for (src in ToolProviderSource.values()) {
            val id = "tool_${src.name.lowercase()}"
            catalog.register(
                ToolRegistration(
                    definition = ToolDefinition(id = id, name = id, description = id),
                    source = src,
                    locality = when (src) {
                        ToolProviderSource.MCP -> ToolExecutionLocality.REMOTE
                        else -> ToolExecutionLocality.LOCAL
                    },
                ),
            )
        }
        assertEquals(3, catalog.size())
        assertEquals(ToolProviderSource.MCP, catalog.findRegistration("tool_mcp")!!.source)
    }

    @Test
    fun resolveDefinitionForExecutionRequest() {
        val catalog = CatalogToolRegistry()
        catalog.register(ToolRegistration(definition = flashlightDef()))
        assertEquals("flashlight", catalog.resolveDefinitionForExecution("flashlight")!!.id)
        assertNull(catalog.resolveDefinitionForExecution("missing"))
    }

    @Test
    fun registryDoesNotExecuteTools() {
        var executed = false
        val catalog = CatalogToolRegistry()
        val def = flashlightDef()
        catalog.register(ToolRegistration(definition = def))
        catalog.bindExecutable(object : Tool {
            override val definition = def
            override fun execute(call: ToolCall): ToolResult {
                executed = true
                return ToolResult(call.requestId, def.id, ToolResultStatus.SUCCESS, "x")
            }
        })
        // Merely resolving / listing must not execute
        catalog.findExecutable("flashlight")
        catalog.catalogEntries()
        catalog.allDefinitions()
        assertFalse(executed)
    }

    @Test
    fun adapterImportsCapabilityAndExecutable() {
        val caps = CapabilityRegistry()
        val cap = CapabilityDescriptor(
            id = "DEVICE_LIGHT",
            title = "Фонарик",
            description = "Torch",
            argumentSchema = listOf(
                ToolParameter("action", "on|off", required = true),
            ),
            permissions = emptyList(),
            riskLevel = RiskLevel.SAFE,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
            availability = CapabilityAvailability.ALWAYS,
            backingToolId = "flashlight",
            tags = listOf("light"),
        )
        caps.register(cap)

        val catalog = CatalogToolRegistry()
        val nCap = CatalogToolRegistryAdapter.importDefinitionsFromCapabilityRegistry(catalog, caps)
        assertEquals(1, nCap)
        assertEquals("DEVICE_LIGHT", catalog.findRegistration("flashlight")!!.capabilityId)

        // Executable must use the same ToolDefinition as catalog (separation + integrity)
        val def = catalog.findDefinition("flashlight")!!
        val legacy = ToolRegistry()
        legacy.register(stubTool(def))
        val nExec = CatalogToolRegistryAdapter.importExecutablesFromToolRegistry(catalog, legacy)
        assertEquals(1, nExec)
        assertNotNull(catalog.findExecutable("flashlight"))
    }

    @Test
    fun metadataCannotBypassSecurity() {
        try {
            ToolRegistration(
                definition = flashlightDef(),
                metadata = ToolMetadata(extras = mapOf("bypass_gate" to "1")),
            )
            org.junit.Assert.fail("expected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun executionFactoryUsesResolvedDefinitionFromCatalog() {
        // Integration smoke: catalog → 8E factory (no real exec)
        val catalog = CatalogToolRegistry()
        val def = flashlightDef()
        catalog.register(ToolRegistration(definition = def))
        val resolved = catalog.resolveDefinitionForExecution("flashlight")!!

        val action = ProposedAction(
            "flashlight",
            ActionKind.LOCAL_DEVICE_SAFE,
            "Фонарик",
            "flashlight",
            mapOf("action" to "on"),
        )
        val assess = ActionRiskAssessment(
            ActionRiskClass.LOW,
            ActionRiskReasonCode.LOCAL_REVERSIBLE,
            actionId = "flashlight",
            kind = ActionKind.LOCAL_DEVICE_SAFE,
        )
        val binding = ActionIntegrity.binding(action, assess)
        val gate = ActionSafetyGateResult(
            status = com.jarvis.core.autonomy.ActionSafetyGateStatus.PASS,
            verdict = com.jarvis.core.autonomy.AutonomyVerdict(
                type = com.jarvis.core.autonomy.AutonomyVerdictType.ALLOW,
                reasonCode = com.jarvis.core.autonomy.AutonomyReasonCode.POLICY_ALLOW,
                actionRequest = com.jarvis.core.autonomy.ActionRequest(
                    id = "r1",
                    source = com.jarvis.core.autonomy.DecisionSource.USER_UTTERANCE,
                    proposedAction = action,
                ),
            ),
        )
        val created = ToolExecutionRequestFactory.create(
            gateResult = gate,
            toolDefinition = resolved,
            policyBinding = binding,
            riskAssessment = assess,
            presentedAction = action,
        )
        assertTrue(created.isCreated)
    }
}
