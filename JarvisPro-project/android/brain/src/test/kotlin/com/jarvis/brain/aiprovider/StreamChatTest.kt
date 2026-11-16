package com.jarvis.brain.aiprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChatTest {
    private val sseOk = listOf(
        "data: {\"choices\":[{\"delta\":{\"content\":\"Прив\"}}]}",
        "data: {\"choices\":[{\"delta\":{\"content\":\"ет\"}}]}",
        "data: {\"choices\":[{\"delta\":{},\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"completion_tokens_details\":{\"reasoning_tokens\":2}}}]}",
        "data: [DONE]",
    )

    private fun sseTransport(lines: List<String>, code: Int = 200, captured: MutableList<String>? = null, capturedHeaders: MutableList<Map<String, String>>? = null): CloudLlmStreamTransport =
        CloudLlmStreamTransport { url, headers, body, _, _, onLine ->
            captured?.add(body)
            capturedHeaders?.add(headers)
            lines.forEach { if (!onLine(it)) return@CloudLlmStreamTransport code }
            code
        }

    @Test
    fun userAgentSent_inStream() {
        val headersCaptured = mutableListOf<Map<String, String>>()
        val t = sseTransport(sseOk, capturedHeaders = headersCaptured)
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" }, streamTransport = t,
        )
        c.streamChat("m", "sys", "hi", emptyList()) {}
        assertEquals(NaraRouterDefaults.USER_AGENT, headersCaptured.first()["User-Agent"])
    }

    @Test
    fun userAgentSent_inPostJson() {
        val captured = mutableListOf<Map<String, String>>()
        val t = CloudLlmHttpTransport { _, headers, _, _, _ ->
            captured.add(headers); CloudLlmHttpResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
        }
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" }, transport = t,
        )
        c.chatCompletion("m", "sys", "hi", emptyList())
        assertEquals(NaraRouterDefaults.USER_AGENT, captured.first()["User-Agent"])
    }

    @Test
    fun extraBody_stringAndBool_inBody() {
        val bodies = mutableListOf<String>()
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" },
            streamTransport = sseTransport(sseOk, captured = bodies),
        )
        c.streamChat("m", "sys", "hi", emptyList(), extraBody = mapOf("reasoning_effort" to "low", "include_reasoning" to false)) {}
        val body = bodies.first()
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
        // булево без кавычек
        assertTrue(body.contains("\"include_reasoning\":false"))
        assertFalse(body.contains("\"include_reasoning\":\"false\""))
    }

    @Test
    fun extraBody_protectedKeysNotOverridden() {
        val bodies = mutableListOf<String>()
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" },
            streamTransport = sseTransport(sseOk, captured = bodies),
        )
        c.streamChat("real-model", "sys", "hi", emptyList(), extraBody = mapOf("model" to "evil", "stream" to false, "max_tokens" to 1)) {}
        val body = bodies.first()
        // model остался настоящий, stream остался true, max_tokens не 1
        assertTrue(body.contains("\"model\":\"real-model\""))
        assertFalse(body.contains("\"model\":\"evil\""))
        assertTrue(body.contains("\"stream\":true"))
        assertFalse(body.contains("\"max_tokens\":1,"))
    }

    @Test
    fun error429_bodyCaptured() {
        val lines = listOf("", "rate limited: PerDay quota", "data: {\"error\":\"x\"}")
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" },
            streamTransport = sseTransport(lines, code = 429),
        )
        val r = c.streamChat("m", "sys", "hi", emptyList()) {}
        assertEquals(429, r.code)
        assertTrue(r.errorBody.contains("PerDay"))
    }

    @Test
    fun usageParsed() {
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" },
            streamTransport = sseTransport(sseOk),
        )
        var got = ""
        val r = c.streamChat("m", "sys", "hi", emptyList()) { got += it }
        assertEquals("Привет", got)
        assertNotNull(r.usage)
        assertEquals(7, r.usage!!["prompt_tokens"])
        assertEquals(3, r.usage!!["completion_tokens"])
        assertEquals(2, r.usage!!["reasoning_tokens"])
    }

    @Test
    fun usageNull_whenAbsent() {
        val noUsage = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
            "data: [DONE]",
        )
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { "k" },
            streamTransport = sseTransport(noUsage),
        )
        val r = c.streamChat("m", "sys", "hi", emptyList()) {}
        assertNull(r.usage)
    }

    @Test
    fun noKey_401() {
        val c = OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://x/v1" }, apiKeyProvider = { null },
        )
        val r = c.streamChat("m", "sys", "hi", emptyList()) {}
        assertEquals(401, r.code)
        assertEquals("api key missing", r.errorBody)
    }
}
