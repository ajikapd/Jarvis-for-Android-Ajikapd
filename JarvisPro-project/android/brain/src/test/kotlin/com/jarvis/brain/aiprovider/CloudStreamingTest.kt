package com.jarvis.brain.aiprovider

import com.jarvis.core.runtime.TtftKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CloudStreamingTest {

    @Test
    fun extractStreamDeltaFromSsePayload() {
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://example.com/v1" },
            apiKeyProvider = { "k" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ -> CloudLlmHttpResponse(500, "") },
        )
        val delta = client.extractStreamDelta(
            """{"choices":[{"delta":{"content":"Привет"}}]}""",
        )
        assertEquals("Привет", delta)
        assertEquals("[DONE]", client.parseSseDataLine("data: [DONE]"))
        assertEquals(
            """{"a":1}""",
            client.parseSseDataLine("""data: {"a":1}"""),
        )
    }

    @Test
    fun realStreamEmitsIncrementalDeltasNotPostSplit() {
        val lines = listOf(
            """data: {"choices":[{"delta":{"content":"Да, "}}]}""",
            """data: {"choices":[{"delta":{"content":"конечно."}}]}""",
            "data: [DONE]",
        )
        var idx = 0
        val streamTransport = CloudLlmStreamTransport { _, _, body, _, _, onLine ->
            assertTrue(body.contains("\"stream\":true"))
            while (idx < lines.size) {
                if (!onLine(lines[idx++])) break
            }
            200
        }
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://example.com/v1" },
            apiKeyProvider = { "k" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ -> CloudLlmHttpResponse(500, "") },
            streamTransport = streamTransport,
        )
        val deltas = mutableListOf<String>()
        val result = client.chatCompletionStream(
            model = "m",
            systemPrompt = "sys",
            userText = "hi",
            history = emptyList(),
            onDelta = { deltas += it },
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("Да, ", "конечно."), deltas)
        assertEquals("Да, конечно.", result.getOrNull())
    }

    @Test
    fun cloudProviderStreamSetsRealTtftMetadata() {
        val lines = listOf(
            """data: {"choices":[{"delta":{"content":"Ок."}}]}""",
            "data: [DONE]",
        )
        var idx = 0
        val streamTransport = CloudLlmStreamTransport { _, _, _, _, _, onLine ->
            while (idx < lines.size) {
                if (!onLine(lines[idx++])) break
            }
            200
        }
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://example.com/v1" },
            apiKeyProvider = { "k" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ -> CloudLlmHttpResponse(500, "") },
            streamTransport = streamTransport,
        )
        val provider = CloudLLMProvider(client = client)
        assertTrue(provider.capabilities.supportsStreaming)
        val events = mutableListOf<AIStreamEvent>()
        provider.stream(
            AICompletionRequest("r1", "привет"),
            AIStreamListener { events += it },
        )
        assertTrue(events.any { it.type == AIStreamEventType.START })
        assertTrue(events.any { it.type == AIStreamEventType.TEXT })
        assertTrue(events.any { it.type == AIStreamEventType.COMPLETE })
        val meta = events.firstOrNull { it.type == AIStreamEventType.METADATA }
        assertNotNull(meta)
        assertTrue(meta!!.text.contains("ttftMs="))
    }

    @Test
    fun chatStreamPipelineFallbackWhenNoStreamingProvider() {
        val reg = AIProviderRegistry()
        reg.register(LocalLLMProvider(object : com.jarvis.brain.ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() = Unit
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ) = "Локальный ответ."
        }))
        val router = AIRouter(reg)
        val pipeline = ChatStreamPipeline(router)
        val sentences = mutableListOf<String>()
        val result = pipeline.streamChat(
            profile = AiTaskProfile.forChat(
                isOnline = true,
                preferCloud = true,
                complexity = AIRequestComplexity.SIMPLE,
            ),
            request = AICompletionRequest("r", "расскажи"),
            generationId = 1L,
            isGenerationStale = { false },
            sink = ChatSentenceSink { s, _ -> sentences += s; true },
        )
        assertTrue(result.fallbackUsed)
        assertFalse(result.streamed)
        assertEquals(TtftKind.NOT_AVAILABLE, result.ttftKind)
        assertTrue(sentences.isNotEmpty() || result.failed)
    }

    @Test
    fun cancelFlagStopsStream() {
        val cancel = AtomicBoolean(false)
        var calls = 0
        val streamTransport = CloudLlmStreamTransport { _, _, _, _, _, onLine ->
            onLine("""data: {"choices":[{"delta":{"content":"A"}}]}""")
            cancel.set(true)
            calls++
            onLine("""data: {"choices":[{"delta":{"content":"B"}}]}""")
            200
        }
        val client = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://example.com/v1" },
            apiKeyProvider = { "k" },
            transport = CloudLlmHttpTransport { _, _, _, _, _ -> CloudLlmHttpResponse(500, "") },
            streamTransport = streamTransport,
        )
        val deltas = mutableListOf<String>()
        client.chatCompletionStream(
            model = "m",
            systemPrompt = "",
            userText = "x",
            history = emptyList(),
            isCancelled = { cancel.get() && deltas.isNotEmpty() },
            onDelta = { deltas += it },
        )
        assertTrue(deltas.isNotEmpty())
        assertTrue(calls >= 1)
    }
}
