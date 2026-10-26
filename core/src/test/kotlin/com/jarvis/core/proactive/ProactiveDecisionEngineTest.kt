package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorCandidateStatus
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.behavior.PatternFamily
import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Stage 7D: Filter + DecisionPolicy + Engine pipeline.
 */
class ProactiveDecisionEngineTest {

    private val tz = TimeZone.getTimeZone("UTC")
    private val matcher = ContextMatcher(timeZone = { tz })
    private val filter = ProactiveCandidateFilter(
        matcher = matcher,
        relevanceEvaluator = RelevanceEvaluator(matcher = matcher),
        cooldownPolicy = CooldownPolicy(testConfig),
        budget = ProactivityBudget(testConfig),
    )
    private val engine = ProactiveDecisionEngine(
        filter = filter,
        policy = DecisionPolicy(),
        cooldownPolicy = CooldownPolicy(testConfig),
        budget = ProactivityBudget(testConfig),
    )

    companion object {
        private val testConfig = ProactivityConfig(
            globalCooldownMs = 10_000L,
            behaviorCooldownMs = 20_000L,
            fingerprintCooldownMs = 15_000L,
            budgetMaxTokens = 3,
            budgetRefillIntervalMs = 60_000L,
        )
    }

    @Test
    fun headphonesRelevantHighConfidenceConfirmation() {
        val c = headphonesRoutine(confidence = 0.84f)
        val snap = snapshot(headphones = true, app = "com.miui.home", at = atHour(18))
        val state = ProactivityState.initial(testConfig)
        val r = engine.evaluate(c, snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.CONFIRMATION_REQUEST, r.decision.type)
        assertEquals(ProactiveReasonCode.SELECTED, r.decision.reasonCode)
        assertTrue(r.decision.relevance >= 0.55f)
        assertNotEquals(state.globalCooldownUntilMillis, r.nextState.globalCooldownUntilMillis)
    }

    @Test
    fun alreadySatisfiedMusicOpenNoOp() {
        val music = "com.google.android.apps.youtube.music"
        val c = headphonesRoutine(confidence = 0.9f, follow = music)
        val snap = snapshot(headphones = true, app = music, at = atHour(18))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED, r.decision.reasonCode)
        assertEquals(0L, r.nextState.globalCooldownUntilMillis)
    }

    @Test
    fun headphonesAbsentNoOp() {
        val c = headphonesRoutine(confidence = 0.9f)
        val snap = snapshot(headphones = false, app = "com.miui.home", at = atHour(18))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.CONTEXT_MISMATCH, r.decision.reasonCode)
    }

    @Test
    fun lowRelevanceCalculatorNoOp() {
        val c = headphonesRoutine(confidence = 0.9f)
        val snap = snapshot(
            headphones = false,
            app = "com.google.android.calculator",
            at = atHour(3),
        )
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertTrue(
            r.decision.reasonCode == ProactiveReasonCode.CONTEXT_MISMATCH ||
                r.decision.reasonCode == ProactiveReasonCode.LOW_RELEVANCE,
        )
    }

    @Test
    fun disabledNoOp() {
        val c = headphonesRoutine(confidence = 0.9f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val feedback = BehaviorFeedbackView(disabledKeys = setOf(c.patternKey))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), feedback, snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.DISABLED, r.decision.reasonCode)
    }

    @Test
    fun budgetExhaustedNoOp() {
        val c = headphonesRoutine(confidence = 0.9f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val state = ProactivityState.initial(testConfig).copy(
            budgetTokens = 0,
            budgetLastRefillAtMillis = snap.takenAtMillis,
        )
        val r = engine.evaluate(c, snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.BUDGET_EXHAUSTED, r.decision.reasonCode)
    }

    @Test
    fun cooldownBlocksSecondDecision() {
        val c = headphonesRoutine(confidence = 0.9f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val t0 = snap.takenAtMillis
        val first = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), t0)
        assertNotEquals(ProactiveDecisionType.NO_OP, first.decision.type)
        val second = engine.evaluate(c, snap, first.nextState, BehaviorFeedbackView(), t0 + 1_000L)
        assertEquals(ProactiveDecisionType.NO_OP, second.decision.type)
        assertTrue(
            second.decision.reasonCode == ProactiveReasonCode.GLOBAL_COOLDOWN ||
                second.decision.reasonCode == ProactiveReasonCode.BEHAVIOR_COOLDOWN ||
                second.decision.reasonCode == ProactiveReasonCode.DUPLICATE,
        )
    }

    @Test
    fun highConfidenceIrrelevantContextNoOp() {
        val c = headphonesRoutine(confidence = 0.91f)
        val snap = snapshot(headphones = false, app = "com.android.settings", at = atHour(3))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertTrue(c.confidence >= 0.90f)
    }

    @Test
    fun repeatedFingerprintNoOp() {
        val c = headphonesRoutine(confidence = 0.85f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val t0 = snap.takenAtMillis
        val first = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), t0)
        assertNotEquals(ProactiveDecisionType.NO_OP, first.decision.type)
        val fp = first.decision.fingerprint
        assertFalse(fp.isNullOrBlank())
        // past global but same fingerprint still armed
        val t1 = t0 + testConfig.globalCooldownMs
        val second = engine.evaluate(c, snap, first.nextState, BehaviorFeedbackView(), t1)
        assertEquals(ProactiveDecisionType.NO_OP, second.decision.type)
        assertTrue(
            second.decision.reasonCode == ProactiveReasonCode.DUPLICATE ||
                second.decision.reasonCode == ProactiveReasonCode.BEHAVIOR_COOLDOWN,
        )
    }

    @Test
    fun evaluateDoesNotMutateInputState() {
        val c = headphonesRoutine(confidence = 0.85f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val state = ProactivityState.initial(testConfig)
        val beforeTokens = state.budgetTokens
        engine.evaluate(c, snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(beforeTokens, state.budgetTokens)
        assertEquals(0L, state.globalCooldownUntilMillis)
    }

    @Test
    fun deterministicSameInput() {
        val c = headphonesRoutine(confidence = 0.85f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val state = ProactivityState.initial(testConfig)
        val a = engine.evaluate(c, snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        val b = engine.evaluate(c, snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(a.decision.type, b.decision.type)
        assertEquals(a.decision.reasonCode, b.decision.reasonCode)
        assertEquals(a.decision.fingerprint, b.decision.fingerprint)
        assertEquals(a.nextState, b.nextState)
    }

    @Test
    fun nullCandidateNoOp() {
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = engine.evaluate(null, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.NO_CANDIDATE, r.decision.reasonCode)
    }

    @Test
    fun lowConfidenceNotEligible() {
        val c = headphonesRoutine(confidence = 0.20f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val f = filter.filter(
            c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis,
        )
        assertFalse(f.eligible)
        assertEquals(ProactiveReasonCode.LOW_CONFIDENCE, f.reasonCode)
    }

    @Test
    fun suggestionTierForPatternFrequency() {
        val c = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "chrome often",
            confidence = 0.60f,
            evidenceCount = 8,
            firstObservedMillis = atHour(12) - 3L * 86_400_000L,
            lastObservedMillis = atHour(12),
            patternKey = "freq:APP:com.android.chrome",
            entities = mapOf("package" to "com.android.chrome"),
            status = BehaviorCandidateStatus.SUGGEST_ONLY,
        )
        // FREQUENCY without headphones trigger — weak context match; expect NO_OP often
        val snap = snapshot(headphones = false, app = "com.miui.home", at = atHour(12))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        // либо NO_OP (mismatch/low rel), либо SUGGESTION/QUESTION — не confirmation
        assertNotEquals(ProactiveDecisionType.CONFIRMATION_REQUEST, r.decision.type)
    }

    @Test
    fun evaluateBestPicksHigherScore() {
        val weak = headphonesRoutine(confidence = 0.50f, follow = "com.spotify.music")
        val strong = headphonesRoutine(confidence = 0.88f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = engine.evaluateBest(
            listOf(weak, strong),
            snap,
            ProactivityState.initial(testConfig),
            BehaviorFeedbackView(),
            snap.takenAtMillis,
        )
        assertEquals(strong.patternKey, r.decision.behaviorPatternKey)
        assertNotEquals(ProactiveDecisionType.NO_OP, r.decision.type)
    }

    @Test
    fun staleStatusFiltered() {
        val c = headphonesRoutine(confidence = 0.9f).copy(status = BehaviorCandidateStatus.STALE)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val f = filter.filter(
            c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis,
        )
        assertFalse(f.eligible)
        assertEquals(ProactiveReasonCode.STALE, f.reasonCode)
    }

    private fun headphonesRoutine(
        confidence: Float,
        follow: String = "com.google.android.apps.youtube.music",
        hour: Int = 18,
    ): BehaviorCandidate = BehaviorCandidate(
        kind = BehaviorKind.ROUTINE,
        family = PatternFamily.SEQUENCE,
        description = "После наушников Music",
        confidence = confidence,
        evidenceCount = 5,
        firstObservedMillis = atHour(hour) - 5L * 86_400_000L,
        lastObservedMillis = atHour(hour),
        patternKey = "routine:HEADPHONES→$follow@$hour",
        entities = mapOf(
            "trigger" to "HEADPHONES_CONNECTED",
            "follow" to follow,
            "hour" to hour.toString(),
        ),
        uniqueDays = 5,
        status = BehaviorCandidateStatus.SUGGEST_ONLY,
    )

    private fun snapshot(
        headphones: Boolean,
        app: String?,
        at: Long,
    ): ContextSnapshot = ContextSnapshot(
        context = PhoneContext(
            currentApp = app,
            headphonesConnected = headphones,
            screenOn = true,
        ),
        takenAtMillis = at,
    )

    private fun atHour(hour: Int): Long {
        val cal = Calendar.getInstance(tz)
        cal.set(2026, Calendar.SEPTEMBER, 21, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
