package com.jarvis.core.cloud

object ChainConfig {
    const val hedgeAfterMs: Long = 2500
    const val totalDeadlineMs: Long = 15000
    const val maxParallel: Int = 2
    const val dailyExhaustedCooldownMs: Long = 6 * 60 * 60 * 1000L
    const val rateLimitedDefaultSeconds: Int = 20
    const val rateLimitedMaxSeconds: Int = 60
    const val circuitOpenMs: Long = 5 * 60 * 1000L
    const val circuitFailureThreshold: Int = 3
}
