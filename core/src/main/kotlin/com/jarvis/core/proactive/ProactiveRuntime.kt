package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorFeedback
import com.jarvis.core.companion.ContextSnapshot

/**
 * Pure Stage 7E/7.5 orchestrator (без Android / UI / ToolCall).
 * CompanionProactive — тонкий adapter поверх этого.
 */
class ProactiveRuntime(
    private val engine: ProactiveDecisionEngine = ProactiveDecisionEngine(),
    private val cooldownPolicy: CooldownPolicy = CooldownPolicy(),
) {
    @Volatile
    private var state: ProactivityState = ProactivityState.initial()

    /** Последний non-NO_OP для UI panel. */
    @Volatile
    private var lastVisible: ProactiveDecision? = null

    /** Последний evaluate outcome включая NO_OP (только diagnostics). */
    @Volatile
    private var lastEvaluated: ProactiveDecision? = null

    @Volatile
    private var lastEvaluateAt: Long = 0L

    fun state(): ProactivityState = state

    fun lastVisibleDecision(): ProactiveDecision? = lastVisible

    /** Debug/diagnostics: включает NO_OP reason; не для production UI. */
    fun lastEvaluatedDecision(): ProactiveDecision? = lastEvaluated

    fun restore(saved: ProactivityState) {
        state = saved
        // UI ephemeral — не часть ProactivityState persistence
        lastVisible = null
        lastEvaluated = null
        lastEvaluateAt = 0L
    }

    /**
     * Coalesced evaluate. NO_OP не становится visible panel decision.
     *
     * [bypassCoalesce] пропускает только [minIntervalMs] throttle runtime.
     * **Не** обходит DISABLE / cooldown / budget / alreadySatisfied /
     * low relevance / duplicate — всё проходит через [ProactiveDecisionEngine].
     *
     * Alias [force] сохранён для тестов/диагностики и означает то же самое.
     */
    fun evaluate(
        snapshot: ContextSnapshot,
        candidates: List<BehaviorCandidate>,
        feedback: BehaviorFeedbackView,
        nowMillis: Long = snapshot.takenAtMillis,
        minIntervalMs: Long = 2_000L,
        force: Boolean = false,
        bypassCoalesce: Boolean = force,
    ): DecisionEvaluationResult {
        val now = ProactivityState.sanitizeNow(nowMillis)
        if (!bypassCoalesce && now - lastEvaluateAt < minIntervalMs) {
            val coalesced = lastEvaluated ?: ProactiveDecision(
                type = ProactiveDecisionType.NO_OP,
                reasonCode = ProactiveReasonCode.ALREADY_HANDLED,
                reason = "coalesced",
                createdAtMillis = now,
            )
            return DecisionEvaluationResult(
                decision = coalesced,
                nextState = state,
            )
        }
        lastEvaluateAt = now

        val result = engine.evaluateBest(
            candidates = candidates,
            snapshot = snapshot,
            state = state,
            feedback = feedback,
            nowMillis = now,
        )
        state = result.nextState

        val decision = result.decision
        lastEvaluated = decision
        if (decision.type != ProactiveDecisionType.NO_OP) {
            val sameVisible = lastVisible?.let {
                it.type == decision.type &&
                    it.behaviorPatternKey == decision.behaviorPatternKey &&
                    it.fingerprint == decision.fingerprint
            } == true
            if (!sameVisible) {
                lastVisible = decision
            }
        } else {
            // Stage 7.5: не держать stale panel, если тот же behavior уже satisfied / disabled
            val visibleKey = lastVisible?.behaviorPatternKey
            if (!visibleKey.isNullOrBlank() &&
                visibleKey == decision.behaviorPatternKey &&
                (
                    decision.reasonCode == ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED ||
                        decision.reasonCode == ProactiveReasonCode.DISABLED ||
                        decision.reasonCode == ProactiveReasonCode.STALE
                    )
            ) {
                lastVisible = null
            }
        }
        return DecisionEvaluationResult(decision = decision, nextState = state)
    }

    /**
     * После BehaviorLearningService.feedback — обновить proactive state через C API.
     */
    fun afterFeedback(
        patternKey: String,
        feedback: BehaviorFeedback,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProactivityState {
        state = cooldownPolicy.afterFeedback(state, nowMillis, patternKey, feedback)
        if (lastVisible?.behaviorPatternKey == patternKey) {
            lastVisible = null
        }
        return state
    }

    fun clearVisible() {
        lastVisible = null
    }
}
