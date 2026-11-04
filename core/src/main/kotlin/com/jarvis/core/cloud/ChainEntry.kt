package com.jarvis.core.cloud

data class ChainEntry(
    val providerId: String,
    val modelId: String,
    val label: String,
    val baseUrl: String,
    val paramCandidates: List<Map<String, Any>>
)

object DefaultCloudChain {
    fun entries(): List<ChainEntry> = listOf(
        // Порядок по результатам бенча (9б-2b): groq 120b → gemini 3.5-lite → groq qwen → gemini 3.1-lite
        ChainEntry(
            providerId = "groq",
            modelId = "openai/gpt-oss-120b",
            label = "groq gpt-oss-120b",
            baseUrl = "https://api.groq.com/openai/v1",
            paramCandidates = listOf(
                mapOf("reasoning_effort" to "low", "include_reasoning" to false),
                mapOf("reasoning_effort" to "low"),
                emptyMap()
            )
        ),
        ChainEntry(
            providerId = "gemini",
            modelId = "gemini-3.5-flash-lite",
            label = "gemini 3.5 flash lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            paramCandidates = listOf(
                mapOf("reasoning_effort" to "minimal"),
                mapOf("reasoning_effort" to "none"),
                emptyMap()
            )
        ),
        ChainEntry(
            providerId = "groq",
            modelId = "qwen/qwen3.8-27b",
            label = "groq qwen3.8-27b",
            baseUrl = "https://api.groq.com/openai/v1",
            paramCandidates = listOf(
                mapOf("reasoning_effort" to "none", "reasoning_format" to "hidden"),
                mapOf("reasoning_effort" to "none"),
                mapOf("reasoning_format" to "hidden"),
                emptyMap()
            )
        ),
        ChainEntry(
            providerId = "gemini",
            modelId = "gemini-3.1-flash-lite",
            label = "gemini 3.1 flash lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            paramCandidates = listOf(
                mapOf("reasoning_effort" to "none"),
                mapOf("reasoning_effort" to "minimal"),
                emptyMap()
            )
        )
    )

    fun availableEntries(entries: List<ChainEntry>, providersWithKeys: Set<String>): List<ChainEntry> =
        entries.filter { it.providerId in providersWithKeys }
}

