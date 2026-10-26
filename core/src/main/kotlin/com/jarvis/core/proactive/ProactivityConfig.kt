package com.jarvis.core.proactive

/**
 * Единая конфигурация cooldown / budget для Stage 7C.
 * Все тайминги — здесь; логика принимает [nowMillis].
 */
data class ProactivityConfig(
    /** Между любыми proactive decisions (не NO_OP). */
    val globalCooldownMs: Long = 15 * 60_000L,
    /** Повтор того же patternKey после suggestion/question/confirm. */
    val behaviorCooldownMs: Long = 2 * 3_600_000L,
    /** Тот же decision fingerprint. */
    val fingerprintCooldownMs: Long = 30 * 60_000L,
    /**
     * IGNORE cooldown живёт в BehaviorStore (6h).
     * Константа здесь для справки/тестов согласованности — не дублируем запись.
     */
    val ignoreCooldownMs: Long = 6 * 3_600_000L,
    /** После REJECT — мягкий proactive cooldown на key (поверх rejectedKeys). */
    val rejectCooldownMs: Long = 12 * 3_600_000L,
    /** Макс. «токенов» proactive interactions. */
    val budgetMaxTokens: Int = 3,
    /** Интервал пополнения +1 token. */
    val budgetRefillIntervalMs: Long = 60 * 60_000L,
    /** Сколько tokens тратит SUGGESTION / QUESTION / CONFIRMATION_REQUEST. */
    val budgetCostSuggestion: Int = 1,
    val budgetCostQuestion: Int = 1,
    val budgetCostConfirmation: Int = 1,
    val maxFingerprints: Int = 32,
    val maxBehaviorCooldowns: Int = 64,
    val maxRejectCooldowns: Int = 64,
) {
    init {
        require(globalCooldownMs >= 0L)
        require(behaviorCooldownMs >= 0L)
        require(fingerprintCooldownMs >= 0L)
        require(budgetMaxTokens >= 1)
        require(budgetRefillIntervalMs > 0L)
    }

    companion object {
        val DEFAULT = ProactivityConfig()
    }
}
