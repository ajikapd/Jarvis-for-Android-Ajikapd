package com.jarvis.brain.aiprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLLMProviderNaraTest {

    @Test
    fun withoutClientStaysUnavailable() {
        val cloud = CloudLLMProvider(endpointConfigured = true, apiKeyPresent = true)
        assertEquals(AIProviderAvailability.UNAVAILABLE, cloud.availability())
    }

    @Test
    fun withKeyAndTransportBecomesAvailableAndCompletes() {
        var key = "sk-nry-test"
        val transport = CloudLlmHttpTransport { _, headers, body, _, _ ->
            assertTrue(headers["Authorization"]!!.startsWith("Bearer sk-nry"))
            assertTrue(body.contains("\"model\""))
            CloudLlmHttpResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"Привет от облака"}}]}""",
            )
        }
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { NaraRouterDefaults.BASE_URL },
            apiKeyProvider = { key },
            transport = transport,
        )
        val cloud = CloudLLMProvider(client = client, modelProvider = { NaraRouterDefaults.MODEL_FAST })
        assertEquals(AIProviderAvailability.AVAILABLE, cloud.availability())
        val r = cloud.complete(AICompletionRequest("r1", "привет"))
        assertEquals(AICompletionStatus.SUCCESS, r.status)
        assertEquals("Привет от облака", r.text)
    }

    @Test
    fun httpErrorMapsToFailure() {
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { NaraRouterDefaults.BASE_URL },
            apiKeyProvider = { "sk-nry-x" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ ->
                CloudLlmHttpResponse(429, """{"error":"rate"}""")
            },
        )
        val cloud = CloudLLMProvider(client = client)
        val r = cloud.complete(AICompletionRequest("r2", "x"))
        assertEquals(AICompletionStatus.FAILURE, r.status)
        assertEquals(AIProviderErrorCategory.TIMEOUT, r.error!!.category)
    }

    @Test
    fun routerPrefersCloudWhenAvailableAndPreferFlag() {
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { NaraRouterDefaults.BASE_URL },
            apiKeyProvider = { "sk" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ ->
                CloudLlmHttpResponse(
                    200,
                    """{"choices":[{"message":{"content":"cloud"}}]}""",
                )
            },
        )
        val reg = AIProviderRegistry()
        reg.register(LocalLLMProvider(object : com.jarvis.brain.ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() {}
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ) = "local"
        }))
        reg.register(CloudLLMProvider(client = client))
        val sel = AIRouter(reg).select(
            AIRouterContext(
                isOnline = true,
                preferCloudWhenOnline = true,
                complexity = AIRequestComplexity.SIMPLE,
            ),
        )
        assertEquals(CloudLLMProvider.ID, sel.provider!!.id)
        assertEquals(AIRouterReasonCode.ONLINE_SIMPLE_CLOUD_PREF, sel.reasonCode)
    }

    @Test
    fun extractContentParsesJson() {
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { NaraRouterDefaults.BASE_URL },
            apiKeyProvider = { "k" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ -> CloudLlmHttpResponse(500, "") },
        )
        val text = client.extractAssistantContent(
            """{"choices":[{"message":{"content":"ok\nline"}}]}""",
        )
        assertEquals("ok\nline", text)
    }

    @Test
    fun blankKeyNotAvailable() {
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { NaraRouterDefaults.BASE_URL },
            apiKeyProvider = { "  " },
            transport = CloudLlmHttpTransport { _, _, _, _, _ -> CloudLlmHttpResponse(200, "{}") },
        )
        assertFalse(client.hasApiKey())
        val cloud = CloudLLMProvider(client = client)
        assertEquals(AIProviderAvailability.UNAVAILABLE, cloud.availability())
    }
}
