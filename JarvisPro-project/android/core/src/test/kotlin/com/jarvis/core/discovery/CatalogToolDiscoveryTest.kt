package com.jarvis.core.discovery

import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.execution.CatalogToolRegistry
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolMetadata
import com.jarvis.core.execution.ToolProviderSource
import com.jarvis.core.execution.ToolRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogToolDiscoveryTest {

    private fun catalogWithMany(): CatalogToolRegistry {
        val catalog = CatalogToolRegistry()
        val defs = listOf(
            Triple("flashlight", "Фонарик", listOf("light", "фонарик")),
            Triple("volume", "Громкость", listOf("volume", "звук")),
            Triple("brightness", "Яркость", listOf("brightness")),
            Triple("music", "Музыка", listOf("music", "media")),
            Triple("weather", "Погода", listOf("weather", "погода")),
            Triple("sms", "SMS", listOf("sms", "message")),
            Triple("phone.call", "Звонок", listOf("call", "phone")),
            Triple("launcher", "Приложения", listOf("launcher", "app")),
            Triple("timer", "Таймер", listOf("timer")),
            Triple("knowledge", "Знания", listOf("knowledge", "wiki")),
            Triple("system.setting", "Настройки", listOf("setting", "wifi")),
            Triple("clipboard", "Буфер", listOf("clipboard")),
            Triple("files.search", "Файлы", listOf("files")),
            Triple("telegram", "Telegram", listOf("telegram")),
            Triple("screenshot", "Скриншот", listOf("screenshot")),
        )
        defs.forEachIndexed { i, (id, name, tags) ->
            val risk = when {
                id == "sms" || id == "phone.call" -> RiskLevel.SENSITIVE
                id == "system.setting" -> RiskLevel.CONFIRM
                else -> RiskLevel.SAFE
            }
            val availability = when (id) {
                "weather", "knowledge" -> ToolAvailability.NEEDS_ONLINE
                "sms" -> ToolAvailability.NEEDS_PERMISSION
                else -> ToolAvailability.ALWAYS
            }
            val perms = if (id == "sms") listOf("android.permission.SEND_SMS") else emptyList()
            val locality = if (availability == ToolAvailability.NEEDS_ONLINE) {
                ToolExecutionLocality.REMOTE
            } else {
                ToolExecutionLocality.LOCAL
            }
            catalog.register(
                ToolRegistration(
                    definition = ToolDefinition(
                        id = id,
                        name = name,
                        description = "Tool $name #$i",
                        riskLevel = risk,
                        requiredPermissions = perms,
                    ),
                    capabilityId = "CAP_${id.uppercase().replace('.', '_')}",
                    source = ToolProviderSource.NATIVE,
                    locality = locality,
                    availability = availability,
                    metadata = ToolMetadata(tags = tags.toSet()),
                    actionKindHint = ActionKind.READ_ONLY,
                ),
            )
        }
        return catalog
    }

    @Test
    fun flashlightQueryReturnsLightToolNotFullCatalog() {
        val catalog = catalogWithMany()
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover("включи фонарик")
        assertTrue(result.items.isNotEmpty())
        assertEquals("flashlight", result.items.first().toolId)
        assertTrue(result.items.size <= DiscoveryContext.DEFAULT_LIMIT)
        assertTrue(result.items.size < catalog.size())
        DiscoveryBounds.ensureNotFullCatalog(result, catalog.size())
    }

    @Test
    fun neverReturnsEntireCatalogEvenWithBroadQuery() {
        val catalog = catalogWithMany()
        assertTrue(catalog.size() > DiscoveryContext.HARD_CAP)
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover(
            DiscoveryContext(query = "открой приложение настрой wifi музыку таймер", limit = 12),
        )
        assertTrue(result.items.size <= DiscoveryContext.HARD_CAP)
        assertTrue(result.items.size < catalog.size())
    }

    @Test
    fun offlineFiltersRemoteOnlineTools() {
        val catalog = catalogWithMany()
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover(
            DiscoveryContext(
                query = "погода",
                isOnline = false,
                limit = 6,
            ),
        )
        assertTrue(result.items.none { it.toolId == "weather" })
    }

    @Test
    fun onlineAllowsWeather() {
        val catalog = catalogWithMany()
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover(
            DiscoveryContext(query = "какая погода", isOnline = true),
        )
        assertTrue(result.items.any { it.toolId == "weather" })
    }

    @Test
    fun missingPermissionFiltersSms() {
        val catalog = catalogWithMany()
        val discovery = CatalogToolDiscovery(catalog)
        val denied = discovery.discover(
            DiscoveryContext(
                query = "отправь смс",
                grantedPermissions = emptySet(),
            ),
        )
        assertTrue(denied.items.none { it.toolId == "sms" })

        val allowed = discovery.discover(
            DiscoveryContext(
                query = "отправь смс",
                grantedPermissions = setOf("android.permission.SEND_SMS"),
            ),
        )
        assertTrue(allowed.items.any { it.toolId == "sms" })
    }

    @Test
    fun maxRiskFiltersSensitive() {
        val catalog = catalogWithMany()
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover(
            DiscoveryContext(
                query = "позвони другу",
                maxRisk = RiskLevel.SAFE,
            ),
        )
        assertTrue(result.items.none { it.riskLevel.ordinal > RiskLevel.SAFE.ordinal })
    }

    @Test
    fun blankQueryReturnsTinySafeSubset() {
        val catalog = catalogWithMany()
        val discovery = CatalogToolDiscovery(catalog)
        val result = discovery.discover(DiscoveryContext(query = "  ", limit = 6))
        assertTrue(result.items.size <= 4)
        assertTrue(result.items.all { it.riskLevel.ordinal <= RiskLevel.LOW.ordinal })
    }

    @Test
    fun hardCapEnforcedOnContext() {
        try {
            DiscoveryContext(query = "x", limit = 100)
            org.junit.Assert.fail("expected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun discoveryDoesNotExecuteTools() {
        // Catalog has no executables — discovery still works
        val catalog = catalogWithMany()
        assertEquals(0, catalog.executableCount())
        val result = CatalogToolDiscovery(catalog).discover("таймер")
        assertTrue(result.items.any { it.toolId == "timer" })
        assertFalse(result.items.first { it.toolId == "timer" }.hasExecutable)
    }
}
