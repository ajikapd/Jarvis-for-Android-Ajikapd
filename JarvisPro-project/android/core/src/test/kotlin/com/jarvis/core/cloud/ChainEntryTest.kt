package com.jarvis.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainEntryTest {

    @Test
    fun entryStoresFields() {
        val entry = ChainEntry(
            providerId = "groq",
            modelId = "openai/gpt-oss-120b",
            label = "Groq GPT-OSS 120B",
            baseUrl = "https://api.groq.com/openai/v1",
            paramCandidates = listOf(
                mapOf("reasoning_effort" to "low", "include_reasoning" to false),
                emptyMap()
            )
        )
        assertEquals("groq", entry.providerId)
        assertEquals("openai/gpt-oss-120b", entry.modelId)
        assertEquals("Groq GPT-OSS 120B", entry.label)
        assertEquals("https://api.groq.com/openai/v1", entry.baseUrl)
        assertEquals(2, entry.paramCandidates.size)
        assertTrue(entry.paramCandidates[1].isEmpty())
    }

    @Test
    fun emptyMapIsLastCandidate() {
        val entry = ChainEntry(
            providerId = "gemini",
            modelId = "gemini-3.5-flash-lite",
            label = "Gemini 3.5 Flash-Lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            paramCandidates = listOf(
                mapOf("reasoning_effort" to "minimal"),
                emptyMap()
            )
        )
        assertTrue(entry.paramCandidates.last().isEmpty())
    }
}
