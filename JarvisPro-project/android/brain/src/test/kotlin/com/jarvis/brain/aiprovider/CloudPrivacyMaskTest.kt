package com.jarvis.brain.aiprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 3.2: секреты не должны покидать устройство ни в одном поле облачного запроса.
 * Синтетические строки, сети нет — транспорт подменён.
 */
class CloudPrivacyMaskTest {

    private class CapturingTransport : CloudLlmHttpTransport {
        var body: String? = null
        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): CloudLlmHttpResponse {
            this.body = body
            return CloudLlmHttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
    }

    private fun client(transport: CapturingTransport) = OpenAiCompatibleCloudClient(
        baseUrlProvider = { "https://example.invalid/v1" },
        apiKeyProvider = { "test-key" },
        transport = transport,
    )

    @Test
    fun userTextSecretIsMaskedBeforeSending() {
        val t = CapturingTransport()
        client(t).chatCompletion(
            model = "test-model",
            systemPrompt = "system",
            userText = "Ваш код 482913, никому не сообщайте",
            history = emptyList(),
        ).getOrThrow()

        val body = t.body.orEmpty()
        assertFalse("OTP leaked: $body", body.contains("482913"))
        assertTrue(body.contains("[скрыто]"))
        assertTrue(body.contains("никому не сообщайте"))
    }

    @Test
    fun cardNumberInHistoryIsMasked() {
        val t = CapturingTransport()
        client(t).chatCompletion(
            model = "test-model",
            systemPrompt = "system",
            userText = "что там по карте?",
            history = listOf(
                "user: вот карта 4111 1111 1111 1111",
                "assistant: записал",
            ),
        ).getOrThrow()

        val body = t.body.orEmpty()
        assertFalse("card leaked: $body", body.contains("4111 1111 1111 1111"))
        assertTrue(body.contains("[скрыто]"))
        assertTrue(body.contains("\"role\":\"assistant\""))
    }

    @Test
    fun systemPromptSecretIsMasked() {
        val t = CapturingTransport()
        client(t).chatCompletion(
            model = "test-model",
            systemPrompt = "[Память] код 7731 do not share",
            userText = "привет",
            history = emptyList(),
        ).getOrThrow()

        val body = t.body.orEmpty()
        assertFalse("system secret leaked: $body", body.contains("7731"))
        assertTrue(body.contains("[скрыто]"))
    }

    @Test
    fun plainNumbersSurviveUnchanged() {
        val t = CapturingTransport()
        client(t).chatCompletion(
            model = "test-model",
            systemPrompt = "Ты ассистент",
            userText = "позвони 89161234567 в 15:30, встреча 13 ноября 2026, бюджет 1 299 ₽",
            history = emptyList(),
        ).getOrThrow()

        val body = t.body.orEmpty()
        assertTrue(body.contains("89161234567"))
        assertTrue(body.contains("15:30"))
        assertTrue(body.contains("2026"))
        assertTrue(body.contains("1 299"))
        assertFalse(body.contains("[скрыто]"))
    }

    @Test
    fun requestStructureIsUnchanged() {
        val t = CapturingTransport()
        client(t).chatCompletion(
            model = "test-model",
            systemPrompt = "system",
            userText = "user-1",
            history = listOf("user: prev", "assistant: prev-a"),
        ).getOrThrow()

        val body = t.body.orEmpty()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":false") || body.contains("\"max_tokens\""))
        assertEquals(4, Regex("\"role\":").findAll(body).count())
    }

    @Test
    fun buildMessagesKeepsRolesAndMasks() {
        val client = client(CapturingTransport())
        val messages = client.buildMessages(
            systemPrompt = "sys код 1234",
            history = listOf("user: карта 5555 5555 5555 4444", "assistant: ок"),
            userText = "ОТП 5591",
        )
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.first })
        assertTrue(messages.all { !it.second.contains("1234") && !it.second.contains("5591") })
        assertTrue(messages[1].second.contains("[скрыто]"))
    }
}
