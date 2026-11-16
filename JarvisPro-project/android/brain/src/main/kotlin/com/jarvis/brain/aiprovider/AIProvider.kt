package com.jarvis.brain.aiprovider

/**
 * Stage 10: единый интерфейс AI-провайдера.
 *
 * Не Executor. Не Tool Registry. Не AutonomyPolicy.
 * Реальный cloud network на этом этапе не обязателен (stub OK).
 */
interface AIProvider {
    val id: String
    val displayName: String
    val kind: AIProviderKind
    val capabilities: AIProviderCapabilities

    fun availability(): AIProviderAvailability

    /** Подготовить ресурсы (load model). Без side effects вне провайдера. */
    fun warm()

    fun complete(request: AICompletionRequest): AICompletionResult
}

/**
 * Реестр провайдеров Stage 10 (не Tool Registry).
 */
class AIProviderRegistry {
    private val byId = LinkedHashMap<String, AIProvider>()

    fun register(provider: AIProvider) {
        require(provider.id.isNotBlank())
        byId[provider.id] = provider
    }

    fun unregister(id: String) {
        byId.remove(id)
    }

    fun find(id: String): AIProvider? = byId[id]

    fun all(): List<AIProvider> = byId.values.toList()

    fun available(): List<AIProvider> =
        byId.values.filter { it.availability() == AIProviderAvailability.AVAILABLE }

    fun localProviders(): List<AIProvider> =
        byId.values.filter { it.kind == AIProviderKind.LOCAL }

    fun cloudProviders(): List<AIProvider> =
        byId.values.filter { it.kind == AIProviderKind.CLOUD }

    fun size(): Int = byId.size
}
