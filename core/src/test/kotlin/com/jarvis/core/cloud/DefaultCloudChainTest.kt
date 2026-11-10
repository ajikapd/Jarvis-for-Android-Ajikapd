package com.jarvis.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCloudChainTest {

    @Test
    fun entriesReturnsAllProvidersInOrder() {
        val entries = DefaultCloudChain.entries()
        assertEquals(6, entries.size)
        // порядок по бенчу: groq120b, gemini3.5-lite, groq qwen, gemini3.1-lite
        assertEquals("groq", entries[0].providerId)
        assertEquals("openai/gpt-oss-120b", entries[0].modelId)
        assertEquals("https://api.groq.com/openai/v1", entries[0].baseUrl)
        assertEquals("gemini", entries[1].providerId)
        assertEquals("gemini-3.5-flash-lite", entries[1].modelId)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", entries[1].baseUrl)
        assertEquals("groq", entries[2].providerId)
        assertEquals("qwen/qwen3.8-27b", entries[2].modelId)
        assertEquals("gemini", entries[3].providerId)
        assertEquals("gemini-3.1-flash-lite", entries[3].modelId)
        // GigaChat последним: без VPN, Pro раньше Lite
        assertEquals("gigachat", entries[4].providerId)
        assertEquals("GigaChat-2-Pro", entries[4].modelId)
        assertEquals("https://api.giga.chat/v1", entries[4].baseUrl)
        assertEquals("GigaChat-2", entries[5].modelId)
    }

    @Test
    fun gigachatAloneGivesItsOwnEntries() {
        val only = DefaultCloudChain.availableEntries(DefaultCloudChain.entries(), setOf("gigachat"))
        assertEquals(listOf("GigaChat-2-Pro", "GigaChat-2"), only.map { it.modelId })
        assertTrue("gigachat" in DefaultCloudChain.PROVIDERS)
    }

    @Test
    fun groqOssHasThreeCandidatesEndingEmpty() {
        val chain = DefaultCloudChain
        val candidates = DefaultCloudChain.entries().first { it.modelId == "openai/gpt-oss-120b" }.paramCandidates
        assertEquals(3, candidates.size)
        assertEquals("low", candidates[0]["reasoning_effort"])
        assertEquals(false, candidates[0]["include_reasoning"])
        assertEquals("low", candidates[1]["reasoning_effort"])
        assertTrue(candidates[2].isEmpty())
    }

    @Test
    fun qwenHasFourCandidatesEndingEmpty() {
        val chain = DefaultCloudChain
        val candidates = DefaultCloudChain.entries().first { it.modelId == "qwen/qwen3.8-27b" }.paramCandidates
        assertEquals(4, candidates.size)
        assertEquals("none", candidates[0]["reasoning_effort"])
        assertEquals("hidden", candidates[0]["reasoning_format"])
        assertEquals("none", candidates[1]["reasoning_effort"])
        assertEquals("hidden", candidates[2]["reasoning_format"])
        assertTrue(candidates[3].isEmpty())
    }

    @Test
    fun gemini35HasThreeCandidatesEndingEmpty() {
        val chain = DefaultCloudChain
        val candidates = DefaultCloudChain.entries().first { it.modelId == "gemini-3.5-flash-lite" }.paramCandidates
        assertEquals(3, candidates.size)
        assertEquals("minimal", candidates[0]["reasoning_effort"])
        assertEquals("none", candidates[1]["reasoning_effort"])
        assertTrue(candidates[2].isEmpty())
    }

    @Test
    fun gemini31HasThreeCandidatesEndingEmpty() {
        val chain = DefaultCloudChain
        val candidates = DefaultCloudChain.entries().first { it.modelId == "gemini-3.1-flash-lite" }.paramCandidates
        assertEquals(3, candidates.size)
        assertEquals("none", candidates[0]["reasoning_effort"])
        assertEquals("minimal", candidates[1]["reasoning_effort"])
        assertTrue(candidates[2].isEmpty())
    }

    @Test
    fun availableEntriesEmptyWhenNoKeys() {
        val entries = DefaultCloudChain.entries()
        assertTrue(DefaultCloudChain.availableEntries(entries, emptySet()).isEmpty())
    }

    @Test
    fun availableEntriesAllWhenAllKeys() {
        val chain = DefaultCloudChain
        val entries = DefaultCloudChain.entries()
        val allKeys = entries.map { it.providerId }.toSet()
        assertEquals(6, DefaultCloudChain.availableEntries(entries, allKeys).size)
    }
}
