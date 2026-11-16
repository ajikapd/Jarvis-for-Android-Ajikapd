package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorStore

/**
 * Read-only взгляд на Stage 6.5 feedback (источник истины — BehaviorStore).
 * CooldownPolicy не дублирует permanent DISABLE.
 */
data class BehaviorFeedbackView(
    val disabledKeys: Set<String> = emptySet(),
    val rejectedKeys: Set<String> = emptySet(),
    /** patternKey → ignoreUntilMillis (IGNORE ≠ DISABLE). */
    val ignoreUntil: Map<String, Long> = emptyMap(),
) {
    companion object {
        fun from(store: BehaviorStore): BehaviorFeedbackView = BehaviorFeedbackView(
            disabledKeys = store.disabledKeys(),
            rejectedKeys = store.rejectedKeys(),
            ignoreUntil = store.ignoreUntilMap(),
        )
    }
}

/**
 * Immutable proactivity state. Без raw sensitive content.
 */
data class ProactivityState(
    val lastDecisionAtMillis: Long = 0L,
    val lastDecisionType: ProactiveDecisionType? = null,
    val globalCooldownUntilMillis: Long = 0L,
    /** patternKey → until (после показа proactive). */
    val behaviorCooldownUntil: Map<String, Long> = emptyMap(),
    /** fingerprint → until */
    val fingerprintCooldownUntil: Map<String, Long> = emptyMap(),
    /** patternKey → until после REJECT (мягкий). */
    val rejectCooldownUntil: Map<String, Long> = emptyMap(),
    val budgetTokens: Int = ProactivityConfig.DEFAULT.budgetMaxTokens,
    val budgetLastRefillAtMillis: Long = 0L,
    val suggestionCount: Int = 0,
    val questionCount: Int = 0,
    val confirmationCount: Int = 0,
    val recordedNoOpCount: Int = 0,
) {
    fun pruneExpired(nowMillis: Long, config: ProactivityConfig = ProactivityConfig.DEFAULT): ProactivityState {
        val now = sanitizeNow(nowMillis)
        return copy(
            behaviorCooldownUntil = boundMap(
                behaviorCooldownUntil.filterValues { it > now },
                config.maxBehaviorCooldowns,
            ),
            fingerprintCooldownUntil = boundMap(
                fingerprintCooldownUntil.filterValues { it > now },
                config.maxFingerprints,
            ),
            rejectCooldownUntil = boundMap(
                rejectCooldownUntil.filterValues { it > now },
                config.maxRejectCooldowns,
            ),
        )
    }

    companion object {
        fun initial(config: ProactivityConfig = ProactivityConfig.DEFAULT): ProactivityState =
            ProactivityState(budgetTokens = config.budgetMaxTokens)

        fun sanitizeNow(nowMillis: Long): Long = nowMillis.coerceAtLeast(0L)

        fun boundMap(map: Map<String, Long>, max: Int): Map<String, Long> {
            if (map.size <= max) return map
            // Оставляем ближайшие к истечению (актуальнее для cooldown)
            return map.entries
                .sortedBy { it.value }
                .takeLast(max)
                .associate { it.key to it.value }
        }
    }
}

/** Результат проверки cooldown. */
data class CooldownCheckResult(
    val allowed: Boolean,
    val reasonCode: ProactiveReasonCode,
    val blockedUntilMillis: Long? = null,
    val detail: String = "",
)
