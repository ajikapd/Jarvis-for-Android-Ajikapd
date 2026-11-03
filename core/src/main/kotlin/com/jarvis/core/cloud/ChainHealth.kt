package com.jarvis.core.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val mutex = Mutex()

    private fun key(entry: ChainEntry) = "${entry.providerId}/${entry.modelId}"

    private fun get(entry: ChainEntry): State = states.getOrPut(key(entry)) { State() }
    private fun set(entry: ChainEntry, state: State) { states[key(entry)] = state }

    suspend fun isAvailable(entry: ChainEntry): Boolean = mutex.withLock {
        val now = clock()
        val s = get(entry)
        if (s.disabled) return@withLock false
        if (s.dailyExhaustedUntil > now) return@withLock false
        if (s.rateLimitedUntil > now) return@withLock false
        if (s.circuitOpenUntil > now) return@withLock false
        if (s.circuitOpenUntil <= now && s.consecutiveFailures >= config.circuitFailureThreshold && !s.circuitHalfOpen) {
            set(entry, s.copy(circuitHalfOpen = true, circuitProbeInFlight = false))
        }
        val current = get(entry)
        if (current.circuitHalfOpen && current.circuitProbeInFlight) return@withLock false
        true
    }

    suspend fun recordSuccess(entry: ChainEntry) = mutex.withLock {
        val s = get(entry)
        set(entry, s.copy(consecutiveFailures = 0, circuitOpenUntil = 0, circuitHalfOpen = false, circuitProbeInFlight = false))
    }

    suspend fun recordFailure(entry: ChainEntry, errorClass: ErrorClass, retryAfterSeconds: Int? = null, paramsExhausted: Boolean = true) = mutex.withLock {
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

    suspend fun markProbeInFlight(entry: ChainEntry) = mutex.withLock {
        val s = get(entry)
        set(entry, s.copy(circuitProbeInFlight = true))
    }

    suspend fun reset(providerId: String) = mutex.withLock {
        val toRemove = states.keys.filter { it.startsWith("$providerId/") }
        toRemove.forEach { states.remove(it) }
    }

    suspend fun snapshot(): Map<String, State> = mutex.withLock { states.toMap() }
}
