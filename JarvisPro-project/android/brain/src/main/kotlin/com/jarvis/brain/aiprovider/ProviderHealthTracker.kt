package com.jarvis.brain.aiprovider

/**
 * V2-03: bounded provider health with cooldown (not permanent ban after one error).
 */
class ProviderHealthTracker(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val degradedAfterFailures: Int = 2,
    private val unavailableAfterFailures: Int = 4,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(
        var status: ProviderHealthStatus = ProviderHealthStatus.UNKNOWN,
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L,
        var lastErrorCode: String? = null,
        var quota: QuotaState = QuotaState.UNKNOWN,
        var successCount: Int = 0,
        var failureCount: Int = 0,
    )

    private val byId = LinkedHashMap<String, Entry>()

    fun status(providerId: String): ProviderHealthStatus {
        val e = byId[providerId] ?: return ProviderHealthStatus.UNKNOWN
        val now = clock()
        if (e.cooldownUntilMs > 0L && now >= e.cooldownUntilMs) {
            // Cooldown expired → allow retry as DEGRADED
            e.cooldownUntilMs = 0L
            if (e.status == ProviderHealthStatus.UNAVAILABLE) {
                e.status = ProviderHealthStatus.DEGRADED
                e.consecutiveFailures = degradedAfterFailures
            }
        }
        if (e.cooldownUntilMs > now) return ProviderHealthStatus.UNAVAILABLE
        return e.status
    }

    fun quota(providerId: String): QuotaState =
        byId[providerId]?.quota ?: QuotaState.UNKNOWN

    fun recordSuccess(providerId: String) {
        val e = byId.getOrPut(providerId) { Entry() }
        e.consecutiveFailures = 0
        e.cooldownUntilMs = 0L
        e.status = ProviderHealthStatus.AVAILABLE
        e.successCount++
        e.lastErrorCode = null
        if (e.quota == QuotaState.RATE_LIMITED) {
            e.quota = QuotaState.AVAILABLE
        }
    }

    fun recordFailure(
        providerId: String,
        error: AIProviderError?,
        latencyMs: Long = 0L,
    ) {
        val e = byId.getOrPut(providerId) { Entry() }
        e.failureCount++
        e.consecutiveFailures++
        e.lastErrorCode = error?.code
        when {
            error?.category == AIProviderErrorCategory.UNAVAILABLE &&
                (error.code.contains("401") || error.code.contains("403") ||
                    error.message.contains("401") || error.message.contains("403") ||
                    error.code == "CLOUD_NO_KEY") -> {
                e.status = ProviderHealthStatus.UNAVAILABLE
                e.cooldownUntilMs = clock() + cooldownMs * 2
            }
            error?.code == "CLOUD_ERROR" && error.message.contains("HTTP 429") -> {
                e.quota = QuotaState.RATE_LIMITED
                e.status = ProviderHealthStatus.DEGRADED
                e.cooldownUntilMs = clock() + cooldownMs
            }
            error?.category == AIProviderErrorCategory.TIMEOUT ||
                error?.category == AIProviderErrorCategory.NETWORK -> {
                e.status = if (e.consecutiveFailures >= unavailableAfterFailures) {
                    ProviderHealthStatus.UNAVAILABLE
                } else {
                    ProviderHealthStatus.DEGRADED
                }
                e.cooldownUntilMs = clock() + cooldownMs
            }
            e.consecutiveFailures >= unavailableAfterFailures -> {
                e.status = ProviderHealthStatus.UNAVAILABLE
                e.cooldownUntilMs = clock() + cooldownMs
            }
            e.consecutiveFailures >= degradedAfterFailures -> {
                e.status = ProviderHealthStatus.DEGRADED
                e.cooldownUntilMs = clock() + cooldownMs / 2
            }
            else -> e.status = ProviderHealthStatus.DEGRADED
        }
        // latency unused for health but kept for future TTFT coupling
        @Suppress("UNUSED_EXPRESSION")
        latencyMs
    }

    fun snapshot(providerId: String): String {
        val e = byId[providerId]
        val st = status(providerId)
        return if (e == null) "$providerId:$st"
        else "$providerId:$st/q=${e.quota}/fail=${e.consecutiveFailures}"
    }

    fun reset(providerId: String) {
        byId.remove(providerId)
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
