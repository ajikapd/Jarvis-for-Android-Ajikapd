package com.jarvis.core.cloud

class ChainHealth(
    private val clock: () -> Long,
    private val config: ChainConfig = ChainConfig.Default
) {
    data class State(
        val dailyExhaustedUntil: Long = 0,
        val rateLimitedUntil: Long = 0,
        val consecutiveFailures: Int = 0,
        val circuitOpenUntil: Long = 0,
        val circuitHalfOpen: Boolean = false,
        val circuitProbeInFlight: Boolean = false,
        val disabled: Boolean = false
    )

    private val states = mutableMapOf<String, State>()
    private val lock = Object()

    private fun key(entry: ChainEntry) = "${entry.providerId}/${entry.modelId}"

    private fun get(entry: ChainEntry): State = states.getOrPut(key(entry)) { State() }
    private fun set(entry: ChainEntry, state: State) { states[key(entry)] = state }

    @Synchronized
    fun isAvailable(entry: ChainEntry): Boolean {
        val now = clock()
        val s = get(entry)
        if (s.disabled) return false
        if (s.dailyExhaustedUntil > now) return false
        if (s.rateLimitedUntil > now) return false
        if (s.circuitOpenUntil > now) return false
        if (s.circuitOpenUntil <= now && s.consecutiveFailures >= config.circuitFailureThreshold && !s.circuitHalfOpen) {
            set(entry, s.copy(circuitHalfOpen = true, circuitProbeInFlight = false))
        }
        val current = get(entry)
        if (current.circuitHalfOpen && current.circuitProbeInFlight) return false
        return true
    }

    @Synchronized
    fun recordSuccess(entry: ChainEntry) {
        val s = get(entry)
        set(entry, s.copy(consecutiveFailures = 0, circuitOpenUntil = 0, circuitHalfOpen = false, circuitProbeInFlight = false))
    }

    @Synchronized
    fun recordFailure(entry: ChainEntry, errorClass: ErrorClass, retryAfterSeconds: Int? = null, paramsExhausted: Boolean = true) {
        val now = clock()
        val s = get(entry)
        when (errorClass) {
            ErrorClass.RATE_LIMITED_DAILY -> {
                set(entry, s.copy(dailyExhaustedUntil = now + config.dailyExhaustedCooldownMs))
            }
            ErrorClass.RATE_LIMITED -> {
                val seconds = (retryAfterSeconds ?: config.rateLimitedDefaultSeconds).coerceIn(1, config.rateLimitedMaxSeconds)
                set(entry, s.copy(rateLimitedUntil = now + seconds * 1000L))
            }
            ErrorClass.AUTH_OR_REGION, ErrorClass.BLOCKED_BY_CLOUDFLARE -> {
                set(entry, s.copy(disabled = true))
            }
            ErrorClass.BAD_REQUEST -> {
                if (paramsExhausted) {
                    val newFailures = s.consecutiveFailures + 1
                    if (newFailures >= config.circuitFailureThreshold) {
                        set(entry, s.copy(consecutiveFailures = newFailures, circuitOpenUntil = now + config.circuitOpenMs, circuitHalfOpen = false, circuitProbeInFlight = false))
                    } else {
                        set(entry, s.copy(consecutiveFailures = newFailures))
                    }
                }
                // else: параметры не исчерпаны — не сбой здоровья
            }
            ErrorClass.OVERLOADED, ErrorClass.NETWORK_ERROR, ErrorClass.SERVER_ERROR, ErrorClass.EMPTY_RESPONSE -> {
                val newFailures = s.consecutiveFailures + 1
                if (newFailures >= config.circuitFailureThreshold) {
                    set(entry, s.copy(consecutiveFailures = newFailures, circuitOpenUntil = now + config.circuitOpenMs, circuitHalfOpen = false, circuitProbeInFlight = false))
                } else {
                    set(entry, s.copy(consecutiveFailures = newFailures))
                }
            }
            else -> {}
        }
    }

    @Synchronized
    fun markProbeInFlight(entry: ChainEntry) {
        val s = get(entry)
        set(entry, s.copy(circuitProbeInFlight = true))
    }

    /**
     * Атомарная (в рамках монитора) проверка доступности + захват пробы:
     * если запись полуоткрыта — помечает пробу in-flight и возвращает true;
     * если недоступна — false без побочных эффектов.
     */
    @Synchronized
    fun tryAcquireProbe(entry: ChainEntry): Boolean {
        if (!isAvailable(entry)) return false
        val s = get(entry)
        if (s.circuitHalfOpen && !s.circuitProbeInFlight) {
            set(entry, s.copy(circuitProbeInFlight = true))
        }
        return true
    }

    /** Снимает пометку пробы без записи успеха/сбоя (для отменённых попыток). */
    @Synchronized
    fun releaseProbe(entry: ChainEntry) {
        val s = get(entry)
        if (s.circuitHalfOpen || s.circuitProbeInFlight) {
            set(entry, s.copy(circuitProbeInFlight = false))
        }
    }

    @Synchronized
    fun reset(providerId: String) {
        val toRemove = states.keys.filter { it.startsWith("$providerId/") }
        toRemove.forEach { states.remove(it) }
    }

    @Synchronized
    fun snapshot(): Map<String, State> = states.toMap()
}
