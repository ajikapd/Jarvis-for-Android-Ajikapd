package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorFeedback

/**
 * Stage 7C: deterministic cooldown checks.
 * Permanent DISABLE / IGNORE until — из [BehaviorFeedbackView] (BehaviorStore).
 * Global / behavior / fingerprint / reject soft — из [ProactivityState].
 */
class CooldownPolicy(
    private val config: ProactivityConfig = ProactivityConfig.DEFAULT,
) {

    /**
     * Можно ли рассматривать кандидат для proactive decision.
     * [fingerprint] опционален (например patternKey|type|contextBucket).
     */
    fun canConsider(
        patternKey: String,
        nowMillis: Long,
        state: ProactivityState,
        feedback: BehaviorFeedbackView,
        fingerprint: String? = null,
    ): CooldownCheckResult {
        val now = ProactivityState.sanitizeNow(nowMillis)

        if (patternKey in feedback.disabledKeys) {
            return CooldownCheckResult(
                allowed = false,
                reasonCode = ProactiveReasonCode.DISABLED,
                detail = "disabled_in_behavior_store",
            )
        }

        val ignoreUntil = feedback.ignoreUntil[patternKey]
        if (ignoreUntil != null && now < ignoreUntil) {
            return CooldownCheckResult(
                allowed = false,
                reasonCode = ProactiveReasonCode.FEEDBACK_COOLDOWN,
                blockedUntilMillis = ignoreUntil,
                detail = "ignore_until",
            )
        }

        if (patternKey in feedback.rejectedKeys) {
            val rejectUntil = state.rejectCooldownUntil[patternKey]
            if (rejectUntil != null && now < rejectUntil) {
                return CooldownCheckResult(
                    allowed = false,
                    reasonCode = ProactiveReasonCode.FEEDBACK_COOLDOWN,
                    blockedUntilMillis = rejectUntil,
                    detail = "reject_cooldown",
                )
            }
            // rejectedKeys без истёкшего soft-cooldown — всё ещё подавляем proactive
            // (Stage 6.5: REJECTED не suggest до recovery; здесь жёстче для proactive)
            return CooldownCheckResult(
                allowed = false,
                reasonCode = ProactiveReasonCode.FEEDBACK_COOLDOWN,
                detail = "rejected_key",
            )
        }

        if (now < state.globalCooldownUntilMillis) {
            return CooldownCheckResult(
                allowed = false,
                reasonCode = ProactiveReasonCode.GLOBAL_COOLDOWN,
                blockedUntilMillis = state.globalCooldownUntilMillis,
                detail = "global",
            )
        }

        val behaviorUntil = state.behaviorCooldownUntil[patternKey]
        if (behaviorUntil != null && now < behaviorUntil) {
            return CooldownCheckResult(
                allowed = false,
                reasonCode = ProactiveReasonCode.BEHAVIOR_COOLDOWN,
                blockedUntilMillis = behaviorUntil,
                detail = "behavior",
            )
        }

        if (fingerprint != null) {
            val fpUntil = state.fingerprintCooldownUntil[fingerprint]
            if (fpUntil != null && now < fpUntil) {
                return CooldownCheckResult(
                    allowed = false,
                    reasonCode = ProactiveReasonCode.DUPLICATE,
                    blockedUntilMillis = fpUntil,
                    detail = "fingerprint",
                )
            }
        }

        return CooldownCheckResult(
            allowed = true,
            reasonCode = ProactiveReasonCode.EVALUATED,
            detail = "clear",
        )
    }

    /**
     * Зафиксировать, что proactive decision был выдан (не NO_OP).
     * Не пишет в BehaviorStore.
     */
    fun afterProactiveDecision(
        state: ProactivityState,
        nowMillis: Long,
        patternKey: String,
        type: ProactiveDecisionType,
        fingerprint: String? = null,
    ): ProactivityState {
        if (type == ProactiveDecisionType.NO_OP) return state
        val now = ProactivityState.sanitizeNow(nowMillis)
        val pruned = state.pruneExpired(now, config)
        var behavior = pruned.behaviorCooldownUntil +
            (patternKey to now + config.behaviorCooldownMs)
        behavior = ProactivityState.boundMap(behavior, config.maxBehaviorCooldowns)

        var fingerprints = pruned.fingerprintCooldownUntil
        if (fingerprint != null) {
            fingerprints = fingerprints + (fingerprint to now + config.fingerprintCooldownMs)
            fingerprints = ProactivityState.boundMap(fingerprints, config.maxFingerprints)
        }

        return pruned.copy(
            lastDecisionAtMillis = now,
            lastDecisionType = type,
            globalCooldownUntilMillis = now + config.globalCooldownMs,
            behaviorCooldownUntil = behavior,
            fingerprintCooldownUntil = fingerprints,
        )
    }

    /**
     * Отразить feedback на ProactivityState (без дублирования DISABLE в BehaviorStore).
     * DISABLE → ничего не пишем в timed maps (истина в BehaviorStore).
     * IGNORE → не пишем (истина ignoreUntil в BehaviorStore).
     * REJECT → soft rejectCooldown в state.
     * ACCEPT → не disable / не cooldown.
     */
    fun afterFeedback(
        state: ProactivityState,
        nowMillis: Long,
        patternKey: String,
        feedback: BehaviorFeedback,
    ): ProactivityState {
        val now = ProactivityState.sanitizeNow(nowMillis)
        val pruned = state.pruneExpired(now, config)
        return when (feedback) {
            BehaviorFeedback.DISABLE,
            BehaviorFeedback.IGNORE,
            BehaviorFeedback.ACCEPT,
            -> pruned
            BehaviorFeedback.REJECT -> {
                var reject = pruned.rejectCooldownUntil +
                    (patternKey to now + config.rejectCooldownMs)
                reject = ProactivityState.boundMap(reject, config.maxRejectCooldowns)
                pruned.copy(rejectCooldownUntil = reject)
            }
        }
    }
}
