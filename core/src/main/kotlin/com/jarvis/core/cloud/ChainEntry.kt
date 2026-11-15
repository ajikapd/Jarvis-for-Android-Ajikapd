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
        ),
        // GigaChat (Сбер): доступен без VPN, бесплатно для физлиц. Lite быстрее и с большой квотой;
        // Pro — запасной, если Lite недоступна. Параллельно их не запускаем: у физлица один поток.
        ChainEntry(
            providerId = "gigachat",
            modelId = "GigaChat-2",
            label = "gigachat 2 lite",
            baseUrl = GIGACHAT_URL,
            paramCandidates = listOf(emptyMap())
        ),
        ChainEntry(
            providerId = "gigachat",
            modelId = "GigaChat-2-Pro",
            label = "gigachat 2 pro",
            baseUrl = GIGACHAT_URL,
            paramCandidates = listOf(emptyMap())
        ),
        // SafeV OpenAI-compatible API.
        ChainEntry(
            providerId = "safev",
            modelId = SAFEV_MODEL_ID,
            label = "safev nemotron 3.5 lightning",
            baseUrl = SAFEV_BASE_URL,
            paramCandidates = listOf(emptyMap())
        )
    )

    const val GIGACHAT_URL = "https://api.giga.chat/v1"
    const val SAFEV_BASE_URL = "https://api.safev.ru/v1"
    const val SAFEV_MODEL_ID = "nemotron-3-5-lightning"

    /** Провайдеры цепочки: ключ любого из них включает облако. */
    val PROVIDERS = setOf("groq", "gemini", "gigachat", "safev")

    /** Для трудных задач (агент на экране): у GigaChat сначала Pro — Lite путает кнопки и формат. */
    fun strongFirst(entries: List<ChainEntry>): List<ChainEntry> {
        val pro = entries.filter { it.providerId == "gigachat" && it.modelId.contains("Pro") }
        if (pro.isEmpty()) return entries
        val firstGiga = entries.indexOfFirst { it.providerId == "gigachat" }
        val rest = entries - pro.toSet()
        return rest.take(firstGiga) + pro + rest.drop(firstGiga)
    }

    fun availableEntries(entries: List<ChainEntry>, providersWithKeys: Set<String>): List<ChainEntry> =
        entries.filter { it.providerId in providersWithKeys }
}

