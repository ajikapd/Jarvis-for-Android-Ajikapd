package com.jarvis.brain.aiprovider

import com.jarvis.brain.ChatLlmEngine
import com.jarvis.brain.LlmStructuredDecision
import com.jarvis.brain.LlmDecisionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AIProviderTest {

    private class FakeEngine(
        private val ready: Boolean = true,
        private val loaded: Boolean = true,
        private val output: String? = "Привет",
    ) : ChatLlmEngine {
        var warmCalls = 0
        override fun isReady() = ready
        override fun isLoaded() = loaded
        override fun warm() {
            warmCalls++
        }
        override fun generate(
            userText: String,
            history: List<String>,
            memories: String,
            persona: String,
            assembled: com.jarvis.core.llm.AssembledLlmContext?,
        ): String? = output
    }

    @Test
    fun localProviderAvailableWhenEngineLoaded() {
        val p = LocalLLMProvider(FakeEngine())
        assertEquals(AIProviderKind.LOCAL, p.kind)
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
        assertTrue(p.capabilities.offlineCapable)
        assertFalse(p.capabilities.requiresNetwork)
    }

    @Test
    fun localProviderDegradedWhenNotWarmed() {
        val p = LocalLLMProvider(FakeEngine(ready = true, loaded = false))
        assertEquals(AIProviderAvailability.DEGRADED, p.availability())
    }

    @Test
    fun localProviderUnavailableWithoutModel() {
        val p = LocalLLMProvider(FakeEngine(ready = false, loaded = false))
        assertEquals(AIProviderAvailability.UNAVAILABLE, p.availability())
    }

    @Test
    fun localCompleteSuccess() {
        val p = LocalLLMProvider(FakeEngine(output = "Ответ"))
        val r = p.complete(
            AICompletionRequest(requestId = "r1", userText = "привет"),
        )
        assertEquals(AICompletionStatus.SUCCESS, r.status)
        assertEquals("Ответ", r.text)
        assertEquals(LocalLLMProvider.ID, r.providerId)
        assertNull(r.error)
    }

    @Test
    fun localCompleteCancelled() {
        val p = LocalLLMProvider(FakeEngine())
        val r = p.complete(
            AICompletionRequest(requestId = "r2", userText = "x", cancelRequested = true),
        )
        assertEquals(AICompletionStatus.CANCELLED, r.status)
        assertEquals(AIProviderErrorCategory.CANCELLED, r.error!!.category)
    }

    @Test
    fun localCompleteEmptyIsFailure() {
        val p = LocalLLMProvider(FakeEngine(output = null))
        val r = p.complete(AICompletionRequest("r3", "x"))
        assertEquals(AICompletionStatus.FAILURE, r.status)
        assertEquals(AIProviderErrorCategory.GENERATION_FAILED, r.error!!.category)
    }

    @Test
    fun cloudStubNeverAvailableAndNeverSucceeds() {
        val cloud = CloudLLMProvider(endpointConfigured = true, apiKeyPresent = true)
        assertEquals(AIProviderKind.CLOUD, cloud.kind)
        assertEquals(AIProviderAvailability.UNAVAILABLE, cloud.availability())
        assertTrue(cloud.capabilities.requiresNetwork)
        assertFalse(cloud.capabilities.offlineCapable)
        val r = cloud.complete(AICompletionRequest("c1", "hello"))
        assertEquals(AICompletionStatus.FAILURE, r.status)
        assertEquals(AIProviderErrorCategory.UNAVAILABLE, r.error!!.category)
    }

    @Test
    fun registryListsLocalAndCloud() {
        val reg = AIProviderRegistry()
        reg.register(LocalLLMProvider(FakeEngine()))
        reg.register(CloudLLMProvider())
        assertEquals(2, reg.size())
        assertEquals(1, reg.localProviders().size)
        assertEquals(1, reg.cloudProviders().size)
        // Cloud stub unavailable → available() only local if loaded
        assertEquals(1, reg.available().size)
        assertEquals(LocalLLMProvider.ID, reg.available().first().id)
    }

    @Test
    fun adapterExposesChatLlmEngine() {
        val provider = LocalLLMProvider(FakeEngine(output = "ok"))
        val engine: ChatLlmEngine = ChatLlmEngineFromProvider(provider)
        assertTrue(engine.isReady())
        assertTrue(engine.isLoaded())
        assertEquals("ok", engine.generate("hi", emptyList()))
    }

    @Test
    fun metadataCapabilitiesDoNotImplyExecution() {
        val cloud = CloudLLMProvider()
        assertFalse(cloud.capabilities.supportsStreaming)
        // Without client/key — no execution
        val r = cloud.complete(AICompletionRequest("x", "stream please"))
        assertEquals(AICompletionStatus.FAILURE, r.status)
        assertTrue(cloud.capabilities.notes.any { it.contains("openai_compatible") })
    }

    @Test
    fun successCannotCarryError() {
        try {
            AICompletionResult(
                requestId = "r",
                providerId = "p",
                status = AICompletionStatus.SUCCESS,
                text = "x",
                error = AIProviderError(AIProviderErrorCategory.INTERNAL, "E", "m"),
            )
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun warmDelegatesToEngine() {
        val fake = FakeEngine()
        LocalLLMProvider(fake).warm()
        assertEquals(1, fake.warmCalls)
    }
}
