package com.jarvis.brain.aiprovider

/**
 * V2-03: детерминированная latency / route metrics (без ML).
 */
class ProviderMetricsStore(
    private val windowSize: Int = 16,
) {
    private data class Bucket(
        val latencies: ArrayDeque<Long> = ArrayDeque(),
        var success: Int = 0,
        var failure: Int = 0,
        var ttftSum: Long = 0L,
        var ttftCount: Int = 0,
    )

    private val byProvider = LinkedHashMap<String, Bucket>()
    private var cloudOk = 0
    private var localOk = 0
    private var fallbackOk = 0
    private var totalOk = 0

    @Synchronized
    fun record(
        providerId: String,
        kind: AIProviderKind,
        latencyMs: Long,
        success: Boolean,
        fallbackUsed: Boolean,
        ttftMs: Long? = null,
    ) {
        val b = byProvider.getOrPut(providerId) { Bucket() }
        if (success) {
            b.success++
            totalOk++
            when {
                fallbackUsed -> fallbackOk++
                kind == AIProviderKind.CLOUD -> cloudOk++
                else -> localOk++
            }
            if (latencyMs >= 0) {
                b.latencies.addLast(latencyMs)
                while (b.latencies.size > windowSize) b.latencies.removeFirst()
            }
            if (ttftMs != null && ttftMs >= 0) {
                b.ttftSum += ttftMs
                b.ttftCount++
            }
        } else {
            b.failure++
        }
    }

    @Synchronized
    fun averageLatencyMs(providerId: String): Long? {
        val b = byProvider[providerId] ?: return null
        if (b.latencies.isEmpty()) return null
        return b.latencies.sum() / b.latencies.size
    }

    @Synchronized
    fun failureRate(providerId: String): Float {
        val b = byProvider[providerId] ?: return 0f
        val t = b.success + b.failure
        if (t == 0) return 0f
        return b.failure.toFloat() / t
    }

    @Synchronized
    fun averageTtftMs(providerId: String): Long? {
        val b = byProvider[providerId] ?: return null
        if (b.ttftCount == 0) return null
        return b.ttftSum / b.ttftCount
    }

    /**
     * Score 0..1 where higher is better for latency-sensitive routing.
     * Unknown providers get neutral 0.5.
     */
    @Synchronized
    fun speedScore(providerId: String): Float {
        val avg = averageLatencyMs(providerId) ?: return 0.5f
        val fail = failureRate(providerId)
        // Map latency: 200ms→1.0, 5000ms→0.1
        val latScore = (1f - ((avg - 200f) / 4800f).coerceIn(0f, 1f)).coerceIn(0.1f, 1f)
        return (latScore * (1f - fail * 0.7f)).coerceIn(0f, 1f)
    }

    @Synchronized
    fun aggregate(): RouteAggregateMetrics {
        val total = (cloudOk + localOk + fallbackOk).coerceAtLeast(1)
        val allLat = byProvider.values.flatMap { it.latencies }
        val avgCompletion = if (allLat.isEmpty()) null else allLat.sum() / allLat.size
        val ttftPairs = byProvider.values.filter { it.ttftCount > 0 }
        val avgTtft = if (ttftPairs.isEmpty()) {
            null
        } else {
            ttftPairs.sumOf { it.ttftSum } / ttftPairs.sumOf { it.ttftCount.toLong() }
        }
        return RouteAggregateMetrics(
            cloudPercent = cloudOk * 100f / total,
            localPercent = localOk * 100f / total,
            fallbackPercent = fallbackOk * 100f / total,
            averageCompletionMs = avgCompletion,
            averageTtftMs = avgTtft,
            totalSuccess = totalOk,
        )
    }
}

data class RouteAggregateMetrics(
    val cloudPercent: Float,
    val localPercent: Float,
    val fallbackPercent: Float,
    val averageCompletionMs: Long?,
    val averageTtftMs: Long?,
    val totalSuccess: Int,
)
