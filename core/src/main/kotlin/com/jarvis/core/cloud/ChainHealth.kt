package com.jarvis.core.cloud

class ChainHealth(private val clock: () -> Long) {
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
    private fun key(entry: ChainEntry) = "${entry.providerId}/${entry.modelId}"

    private fun get(entry: ChainEntry): State = states.getOrPut(key(entry)) { State() }
    private fun set(entry: ChainEntry, state: State) { states[key(entry)] = state }

    fun isAvailable(entry: ChainEntry): Boolean {
        val now = clock()
        val s = get(entry)
        if (s.disabled) return false
        if (s.dailyExhaustedUntil > now) return false
        if (s.rateLimitedUntil > now) return false
        if (s.circuitOpenUntil > now) return false
        if (s.circuitOpenUntil <= now && s.consecutiveFailures >= ChainConfig.circuitFailureThreshold && !s.circuitHalfOpen) {
            set(entry, s.copy(circuitHalfOpen = true, circuitProbeInFlight = false))
        }
        val current = get(entry)
        if (current.circuitHalfOpen && current.circuitProbeInFlight) return false
        return true
    }

    fun recordSuccess(entry: ChainEntry) {
        val s = get(entry)
        set(entry, s.copy(consecutiveFailures = 0, circuitOpenUntil = 0, circuitHalfOpen = false, circuitProbeInFlight = false))
    }

    fun recordFailure(entry: ChainEntry, errorClass: ErrorClass, retryAfterSeconds: Int? = null) {
        val now = clock()
        val s = get(entry)
        when (errorClass) {
            ErrorClass.RATE_LIMITED_DAILY -> {
                set(entry, s.copy(dailyExhaustedUntil = now + ChainConfig.dailyExhaustedCooldownMs))
            }
            ErrorClass.RATE_LIMITED -> {
                val seconds = (retryAfterSeconds ?: ChainConfig.rateLimitedDefaultSeconds).coerceIn(1, ChainConfig.rateLimitedMaxSeconds)
                set(entry, s.copy(rateLimitedUntil = now + seconds * 1000L))
            }
            ErrorClass.AUTH_OR_REGION, ErrorClass.BLOCKED_BY_CLOUDFLARE -> {
                set(entry, s.copy(disabled = true))
            }
            ErrorClass.OVERLOADED, ErrorClass.NETWORK_ERROR, ErrorClass.SERVER_ERROR, ErrorClass.EMPTY_RESPONSE, ErrorClass.BAD_REQUEST -> {
                val newFailures = s.consecutiveFailures + 1
                if (newFailures >= ChainConfig.circuitFailureThreshold) {
                    set(entry, s.copy(consecutiveFailures = newFailures, circuitOpenUntil = now + ChainConfig.circuitOpenMs, circuitHalfOpen = false, circuitProbeInFlight = false))
                } else {
                    set(entry, s.copy(consecutiveFailures = newFailures))
                }
            }
            else -> {}
        }
    }

    fun markProbeInFlight(entry: ChainEntry) {
        val s = get(entry)
        set(entry, s.copy(circuitProbeInFlight = true))
    }

    fun reset(providerId: String) {
        val toRemove = states.keys.filter { it.startsWith("$providerId/") }
        toRemove.forEach { states.remove(it) }
    }

    fun snapshot(): Map<String, State> = states.toMap()
}
