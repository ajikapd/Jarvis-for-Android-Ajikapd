package com.jarvis.core.cloud

data class ChainConfig(
    val hedgeAfterMs: Long = 2500,
    val totalDeadlineMs: Long = 15000,
    /** Таймаут подключения для вызовов цепочки (9б-2c A2); чтение ограничено totalDeadlineMs. */
    val connectTimeoutMs: Long = 4_000,
    val maxParallel: Int = 2,
    val dailyExhaustedCooldownMs: Long = 6 * 60 * 60 * 1000L,
    val rateLimitedDefaultSeconds: Int = 20,
    val rateLimitedMaxSeconds: Int = 60,
    val circuitOpenMs: Long = 5 * 60 * 1000L,
    val circuitFailureThreshold: Int = 3,
    /** После первого токена дедлайна нет: стрим обрывается только если токены перестали идти. */
    val streamIdleTimeoutMs: Long = 8_000,
    /** Пауза записи после 403: блокировка региона/сети снимается сменой сети, а не ключа. */
    val blockedCooldownMs: Long = 5 * 60 * 1000L,
    /** Провайдеры, которые принимают только один запрос за раз: их записи не запускаются параллельно. */
    val singleStreamProviders: Set<String> = setOf("gigachat"),
) {
    companion object {
        val Default = ChainConfig()
    }
}

