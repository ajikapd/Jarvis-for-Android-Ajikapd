package com.jarvis.brain.aiprovider

import com.jarvis.brain.ChatLlmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-03 Hybrid AI Router policy tests.
 */
class AIRouterPolicyTest {

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

    private class ControllableCloud(
        @Volatile var available: Boolean = true,
        @Volatile var fail: Boolean = false,
        @Volatile var latencyMs: Long = 50L,
        private val model: String = "cloud-model",
    ) : AIProvider {
        override val id = "cloud.llm"
        override val displayName = "Cloud"
        override val kind = AIProviderKind.CLOUD
        override val capabilities = AIProviderCapabilities(
            requiresNetwork = true,
            supportsStructuredDecide = true,
            defaultModelId = model,
            qualityTier = QualityRequirement.QUALITY,
            speedTier = QualityRequirement.FAST,
            maxContextTokens = 8192,
        )
        override fun availability() =
            if (available) AIProviderAvailability.AVAILABLE else AIProviderAvailability.UNAVAILABLE
        override fun warm() {}
        override fun complete(request: AICompletionRequest): AICompletionResult {
            if (fail) {
                return AICompletionResult(
                    requestId = request.requestId,
                    providerId = id,
                    status = AICompletionStatus.FAILURE,
                    latencyMs = latencyMs,
                    modelId = request.preferredModelId ?: model,
                    error = AIProviderError(
                        AIProviderErrorCategory.TIMEOUT,
                        "CLOUD_ERROR",
                        "HTTP 504 timeout",
                    ),
                )
            }
            return AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.SUCCESS,
                text = "cloud-ok",
                latencyMs = latencyMs,
                modelId = request.preferredModelId ?: model,
                ttftMs = latencyMs / 2,
            )
        }
    }

    private fun reg(
        cloud: ControllableCloud? = ControllableCloud(),
        localReady: Boolean = true,
    ): Pair<AIProviderRegistry, ControllableCloud?> {
        val r = AIProviderRegistry()
        r.register(LocalLLMProvider(FakeEngine(ready = localReady, loaded = localReady)))
        if (cloud != null) r.register(cloud)
        return r to cloud
    }

    @Test
    fun offlineForcesLocal() {
        val (registry, _) = reg()
        val sel = AIRouter(registry).select(
            AiTaskProfile.forChat(isOnline = false, preferCloud = true, complexity = AIRequestComplexity.SIMPLE),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.OFFLINE_LOCAL, sel.reasonCode)
    }

    @Test
    fun privacyLocalRequiredNeverCloud() {
        val (registry, cloud) = reg()
        cloud!!.fail = true
        val router = AIRouter(registry)
        val (sel, result) = router.complete(
            AiTaskProfile(
                taskType = AiTaskType.CHAT,
                privacyLevel = PrivacyLevel.LOCAL_REQUIRED,
                isOnline = true,
                preferCloud = true,
                fallbackAllowed = true,
                requestId = "p1",
            ),
            AICompletionRequest("p1", "секретный вопрос"),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AICompletionStatus.SUCCESS, result.status)
        assertEquals(AIRouterReasonCode.PRIVACY_LOCAL_ONLY, sel.reasonCode)
    }

    @Test
    fun preferCloudSelectsCloudWhenOnline() {
        val (registry, _) = reg()
        val sel = AIRouter(registry).select(
            AiTaskProfile.forChat(
                isOnline = true,
                preferCloud = true,
                complexity = AIRequestComplexity.SIMPLE,
            ),
        )
        assertEquals(CloudLLMProvider.ID, sel.provider!!.id)
        assertTrue(
            sel.reasonCode == AIRouterReasonCode.ONLINE_SIMPLE_CLOUD_PREF ||
                sel.reasonCode == AIRouterReasonCode.LATENCY_PRIORITY,
        )
        assertNotNull(sel.modelId)
    }

    @Test
    fun cloudFailureFallsBackToLocal() {
        val (registry, cloud) = reg()
        cloud!!.fail = true
        val router = AIRouter(registry)
        val (sel, result) = router.complete(
            AiTaskProfile.forChat(
                isOnline = true,
                preferCloud = true,
                complexity = AIRequestComplexity.SIMPLE,
                requestId = "f1",
            ),
            AICompletionRequest("f1", "привет"),
        )
        assertEquals(AICompletionStatus.SUCCESS, result.status)
        assertEquals(LocalLLMProvider.ID, result.providerId)
        assertTrue(sel.fallbackUsed)
        assertTrue(router.lastAttempts.size >= 2)
    }

    @Test
    fun semanticInterpretationPrefersLocal() {
        val (registry, _) = reg()
        val sel = AIRouter(registry).select(
            AiTaskProfile.forSemanticInterpretation(isOnline = true, preferCloud = false),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.STRUCTURED_LOCAL, sel.reasonCode)
    }

    @Test
    fun reasoningPrefersCloud() {
        val (registry, _) = reg()
        val sel = AIRouter(registry).select(
            AiTaskProfile.forPlanning(isOnline = true, preferCloud = true),
        )
        assertEquals(CloudLLMProvider.ID, sel.provider!!.id)
        assertTrue(
            sel.reasonCode == AIRouterReasonCode.ONLINE_QUALITY ||
                sel.reasonCode == AIRouterReasonCode.ONLINE_COMPLEX_CLOUD,
        )
        assertEquals(NaraRouterDefaults.MODEL_QUALITY, sel.modelId)
    }

    @Test
    fun healthCooldownSkipsFailingCloud() {
        val (registry, cloud) = reg()
        val health = ProviderHealthTracker(cooldownMs = 60_000L)
        val router = AIRouter(registry, health = health)
        repeat(4) {
            health.recordFailure(
                CloudLLMProvider.ID,
                AIProviderError(AIProviderErrorCategory.NETWORK, "CLOUD_ERROR", "fail"),
            )
        }
        assertEquals(ProviderHealthStatus.UNAVAILABLE, health.status(CloudLLMProvider.ID))
        val sel = router.select(
            AiTaskProfile.forChat(isOnline = true, preferCloud = true, complexity = AIRequestComplexity.SIMPLE),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertTrue(sel.fallbackUsed || sel.reasonCode == AIRouterReasonCode.CLOUD_UNAVAILABLE)
        // silence unused
        assertNotNull(cloud)
    }

    @Test
    fun latencyMetricsPreferFasterProvider() {
        val local = LocalLLMProvider(FakeEngine())
        val slowCloud = ControllableCloud(latencyMs = 5000L)
        val reg = AIProviderRegistry()
        reg.register(local)
        reg.register(slowCloud)
        val metrics = ProviderMetricsStore()
        // Seed: local fast, cloud slow
        repeat(5) {
            metrics.record(LocalLLMProvider.ID, AIProviderKind.LOCAL, 80L, true, false)
            metrics.record(CloudLLMProvider.ID, AIProviderKind.CLOUD, 4000L, true, false)
        }
        val router = AIRouter(reg, metrics = metrics)
        val sel = router.select(
            AiTaskProfile(
                taskType = AiTaskType.CHAT,
                isOnline = true,
                preferCloud = false,
                latencySensitivity = LatencySensitivity.HIGH,
                qualityRequirement = QualityRequirement.FAST,
                requestId = "lat",
            ),
        )
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.LATENCY_PRIORITY, sel.reasonCode)
    }

    @Test
    fun streamingRequiredSkipsNonStreamingWhenPossible() {
        val streamingCloud = object : AIProvider {
            override val id = "cloud.stream"
            override val displayName = "Stream"
            override val kind = AIProviderKind.CLOUD
            override val capabilities = AIProviderCapabilities(
                supportsStreaming = true,
                requiresNetwork = true,
                defaultModelId = "stream-1",
            )
            override fun availability() = AIProviderAvailability.AVAILABLE
            override fun warm() {}
            override fun complete(request: AICompletionRequest) =
                AICompletionResult(request.requestId, id, AICompletionStatus.SUCCESS, text = "s")
        }
        val reg = AIProviderRegistry()
        reg.register(LocalLLMProvider(FakeEngine()))
        reg.register(ControllableCloud())
        reg.register(streamingCloud)
        val sel = AIRouter(reg).select(
            AiTaskProfile(
                taskType = AiTaskType.CHAT,
                isOnline = true,
                preferCloud = true,
                streamingRequired = true,
            ),
        )
        assertEquals("cloud.stream", sel.provider!!.id)
        assertEquals(AIRouterReasonCode.STREAMING_CAPABLE, sel.reasonCode)
    }

    @Test
    fun aggregateMetricsTrackCloudLocalFallback() {
        val (registry, _) = reg()
        val router = AIRouter(registry)
        router.complete(
            AiTaskProfile.forChat(true, preferCloud = true, AIRequestComplexity.SIMPLE, requestId = "a1"),
            AICompletionRequest("a1", "hi"),
        )
        val agg = router.aggregateMetrics()
        assertTrue(agg.totalSuccess >= 1)
        assertFalse(agg.cloudPercent.isNaN())
    }

    @Test
    fun legacyContextStillWorks() {
        val (registry, _) = reg(cloud = null)
        val sel = AIRouter(registry).select(AIRouterContext(isOnline = false))
        assertEquals(LocalLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.OFFLINE_LOCAL, sel.reasonCode)
    }
}
