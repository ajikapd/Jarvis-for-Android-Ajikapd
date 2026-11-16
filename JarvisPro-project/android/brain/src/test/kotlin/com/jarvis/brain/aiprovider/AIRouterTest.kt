package com.jarvis.brain.aiprovider

import com.jarvis.brain.ChatLlmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AIRouterTest {

    private class FakeEngine(
        private val ready: Boolean = true,
        private val loaded: Boolean = true,
        private val output: String? = "local-ok",
    ) : ChatLlmEngine {
        override fun isReady() = ready
        override fun isLoaded() = loaded
        override fun warm() {}
        override fun generate(
            userText: String,
            history: List<String>,
            memories: String,
            persona: String,
            assembled: com.jarvis.core.llm.AssembledLlmContext?,
        ): String? = output
    }

    private fun registry(
        localReady: Boolean = true,
        localLoaded: Boolean = true,
        cloudConfigured: Boolean = false,
    ): AIProviderRegistry {
        val reg = AIProviderRegistry()
        reg.register(
            LocalLLMProvider(FakeEngine(ready = localReady, loaded = localLoaded)),
        )
        reg.register(
            CloudLLMProvider(
                endpointConfigured = cloudConfigured,
                apiKeyPresent = cloudConfigured,
            ),
        )
        return reg
    }

    @Test
    fun offlineSelectsLocal() {
        val router = AIRouter(registry())
        val sel = router.select(AIRouterContext(isOnline = false))
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.OFFLINE_LOCAL, sel.reasonCode)
    }

    @Test
    fun onlineSimpleSelectsLocalByDefault() {
        val router = AIRouter(registry())
        val sel = router.select(
            AIRouterContext(isOnline = true, complexity = AIRequestComplexity.SIMPLE),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.ONLINE_SIMPLE_LOCAL, sel.reasonCode)
    }

    @Test
    fun onlineComplexFallsBackToLocalWhenCloudStub() {
        val router = AIRouter(registry(cloudConfigured = true))
        val sel = router.select(
            AIRouterContext(
                isOnline = true,
                complexity = AIRequestComplexity.COMPLEX,
                preferCloudWhenOnline = true,
            ),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertTrue(sel.fallbackUsed)
        assertEquals(AIRouterReasonCode.FALLBACK_LOCAL, sel.reasonCode)
    }

    @Test
    fun privacyForcesLocalEvenIfPreferCloud() {
        val router = AIRouter(registry(cloudConfigured = true))
        val sel = router.select(
            AIRouterContext(
                isOnline = true,
                privacyRestricted = true,
                preferCloudWhenOnline = true,
                complexity = AIRequestComplexity.COMPLEX,
            ),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.PRIVACY_LOCAL_ONLY, sel.reasonCode)
    }

    @Test
    fun preferredProviderHonoredWhenAvailable() {
        val router = AIRouter(registry())
        val sel = router.select(
            AIRouterContext(
                isOnline = true,
                preferredProviderId = LocalLLMProvider.ID,
            ),
        )
        assertEquals(AIRouterReasonCode.PREFERRED, sel.reasonCode)
    }

    @Test
    fun noneWhenLocalMissing() {
        val reg = AIProviderRegistry()
        reg.register(CloudLLMProvider())
        val router = AIRouter(reg)
        val sel = router.select(AIRouterContext(isOnline = false))
        assertNull(sel.provider)
        assertEquals(AIRouterReasonCode.NONE_AVAILABLE, sel.reasonCode)
    }

    @Test
    fun completeUsesSelectedProvider() {
        val router = AIRouter(registry())
        val (sel, result) = router.complete(
            AIRouterContext(isOnline = false),
            AICompletionRequest("r1", "привет"),
        )
        assertNotNull(sel.provider)
        assertEquals(AICompletionStatus.SUCCESS, result.status)
        assertEquals("local-ok", result.text)
    }

    @Test
    fun cloudNotMandatory_onlineWorksWithLocalOnly() {
        val reg = AIProviderRegistry()
        reg.register(LocalLLMProvider(FakeEngine()))
        // no cloud registered
        val sel = AIRouter(reg).select(AIRouterContext(isOnline = true))
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertFalse(sel.fallbackUsed)
    }

    @Test
    fun degradedLocalStillSelectableOffline() {
        val router = AIRouter(registry(localReady = true, localLoaded = false))
        val sel = router.select(AIRouterContext(isOnline = false))
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIProviderAvailability.DEGRADED, sel.provider!!.availability())
    }

    @Test
    fun onlineComplexSelectsAvailableCloud() {
        val reg = AIProviderRegistry()
        reg.register(LocalLLMProvider(FakeEngine()))
        reg.register(object : AIProvider {
            override val id = "cloud.fake"
            override val displayName = "Fake Cloud"
            override val kind = AIProviderKind.CLOUD
            override val capabilities = AIProviderCapabilities(requiresNetwork = true)
            override fun availability() = AIProviderAvailability.AVAILABLE
            override fun warm() {}
            override fun complete(request: AICompletionRequest) =
                AICompletionResult(request.requestId, id, AICompletionStatus.SUCCESS, text = "cloud")
        })
        val sel = AIRouter(reg).select(
            AIRouterContext(
                isOnline = true,
                complexity = AIRequestComplexity.COMPLEX,
                preferCloudWhenOnline = true,
            ),
        )
        assertEquals("cloud.fake", sel.provider!!.id)
        assertTrue(
            sel.reasonCode == AIRouterReasonCode.ONLINE_COMPLEX_CLOUD ||
                sel.reasonCode == AIRouterReasonCode.ONLINE_QUALITY,
        )
        assertFalse(sel.fallbackUsed)
    }
}
