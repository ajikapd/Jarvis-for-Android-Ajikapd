package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorCandidateStatus
import com.jarvis.core.behavior.BehaviorFeedback
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.behavior.BehaviorStore
import com.jarvis.core.behavior.PatternFamily
import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Stage 7.5: force safety, evaluateBest ranking, conf×rel matrix, anti-storm, budget.
 */
class ProactiveHardeningTest {

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

    // --- force / bypassCoalesce ---

    @Test
    fun forceDoesNotBypassDisabled() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val c = headphones(0.9f)
        val store = BehaviorStore()
        store.upsertSuggestions(listOf(c))
        store.applyFeedback(c.patternKey, BehaviorFeedback.DISABLE)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = runtime.evaluate(
            snap,
            store.activeCandidates(),
            BehaviorFeedbackView.from(store),
            force = true,
        )
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertNull(runtime.lastVisibleDecision())
    }

    @Test
    fun forceDoesNotBypassAlreadySatisfied() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val music = "com.google.android.apps.youtube.music"
        val c = headphones(0.9f, follow = music)
        val snap = snapshot(headphones = true, app = music, at = atHour(18))
        val r = runtime.evaluate(
            snap,
            listOf(c),
            BehaviorFeedbackView(),
            force = true,
        )
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED, r.decision.reasonCode)
        assertNull(runtime.lastVisibleDecision())
    }

    @Test
    fun forceDoesNotBypassBudget() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val c = headphones(0.9f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        runtime.restore(
            ProactivityState.initial(testConfig).copy(
                budgetTokens = 0,
                budgetLastRefillAtMillis = snap.takenAtMillis,
            ),
        )
        val r = runtime.evaluate(snap, listOf(c), BehaviorFeedbackView(), force = true)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(ProactiveReasonCode.BUDGET_EXHAUSTED, r.decision.reasonCode)
    }

    @Test
    fun forceDoesNotBypassLowRelevance() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val c = headphones(0.9f)
        val snap = snapshot(headphones = false, app = "com.android.settings", at = atHour(3))
        val r = runtime.evaluate(snap, listOf(c), BehaviorFeedbackView(), force = true)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertTrue(
            r.decision.reasonCode == ProactiveReasonCode.CONTEXT_MISMATCH ||
                r.decision.reasonCode == ProactiveReasonCode.LOW_RELEVANCE,
        )
    }

    @Test
    fun forceDoesNotBypassDuplicateCooldown() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val c = headphones(0.88f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val first = runtime.evaluate(snap, listOf(c), BehaviorFeedbackView(), force = true)
        assertNotEquals(ProactiveDecisionType.NO_OP, first.decision.type)
        val second = runtime.evaluate(
            snap,
            listOf(c),
            BehaviorFeedbackView(),
            nowMillis = snap.takenAtMillis + 1_000L,
            force = true,
        )
        assertEquals(ProactiveDecisionType.NO_OP, second.decision.type)
        assertTrue(
            second.decision.reasonCode == ProactiveReasonCode.GLOBAL_COOLDOWN ||
                second.decision.reasonCode == ProactiveReasonCode.BEHAVIOR_COOLDOWN ||
                second.decision.reasonCode == ProactiveReasonCode.DUPLICATE,
        )
    }

    // --- evaluateBest ranking ---

    @Test
    fun alreadySatisfiedClearsStaleVisiblePanel() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val music = "com.google.android.apps.youtube.music"
        val c = headphones(0.88f, follow = music)
        val open = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val first = runtime.evaluate(open, listOf(c), BehaviorFeedbackView(), force = true)
        assertNotEquals(ProactiveDecisionType.NO_OP, first.decision.type)
        assertNotNull(runtime.lastVisibleDecision())

        val satisfied = snapshot(headphones = true, app = music, at = atHour(18) + 120_000L)
        // past global cooldown window
        runtime.restore(first.nextState.copy(globalCooldownUntilMillis = 0L, behaviorCooldownUntil = emptyMap(), fingerprintCooldownUntil = emptyMap()))
        // restore clears visible — re-seed
        runtime.evaluate(open, listOf(c), BehaviorFeedbackView(), nowMillis = open.takenAtMillis, force = true)
        assertNotNull(runtime.lastVisibleDecision())
        val cleared = runtime.evaluate(
            satisfied,
            listOf(c),
            BehaviorFeedbackView(),
            nowMillis = satisfied.takenAtMillis,
            force = true,
        )
        assertEquals(ProactiveDecisionType.NO_OP, cleared.decision.type)
        assertEquals(ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED, cleared.decision.reasonCode)
        assertNull(runtime.lastVisibleDecision())
    }

    @Test
    fun packageOnlyFrequencyDoesNotMatchAlone() {
        val telegram = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "telegram often",
            confidence = 0.71f,
            evidenceCount = 6,
            firstObservedMillis = atHour(22) - 86_400_000L,
            lastObservedMillis = atHour(22),
            patternKey = "freq:APP:org.telegram.messenger",
            entities = mapOf("package" to "org.telegram.messenger"),
            uniqueDays = 1,
            status = BehaviorCandidateStatus.SUGGEST_ONLY,
        )
        val snap = snapshot(headphones = false, app = "com.jarvis.assistant", at = atHour(22))
        val r = engine.evaluate(telegram, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertTrue(
            r.decision.reasonCode == ProactiveReasonCode.CONTEXT_MISMATCH ||
                r.decision.reasonCode == ProactiveReasonCode.LOW_RELEVANCE,
        )
    }

    @Test
    fun headphonesRoutineBeatsUnrelatedFrequencyWhenConnected() {
        val routine = headphones(0.85f)
        val telegram = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "telegram often",
            confidence = 0.71f,
            evidenceCount = 6,
            firstObservedMillis = atHour(22) - 86_400_000L,
            lastObservedMillis = atHour(22),
            patternKey = "freq:APP:org.telegram.messenger",
            entities = mapOf("package" to "org.telegram.messenger"),
            uniqueDays = 1,
            status = BehaviorCandidateStatus.SUGGEST_ONLY,
        )
        val snap = snapshot(headphones = true, app = "com.miui.home", at = atHour(22))
        val r = engine.evaluateBest(
            listOf(telegram, routine),
            snap,
            ProactivityState.initial(testConfig),
            BehaviorFeedbackView(),
            snap.takenAtMillis,
        )
        assertNotEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(routine.patternKey, r.decision.behaviorPatternKey)
        assertEquals(ProactiveDecisionType.CONFIRMATION_REQUEST, r.decision.type)
    }

    @Test
    fun evaluateBestPrefersHigherRelevanceOverHigherConfidence() {
        // high conf + weaker context vs lower conf + strong headphones routine
        val highConfWeak = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "chrome often",
            confidence = 0.91f,
            evidenceCount = 12,
            firstObservedMillis = atHour(18) - 5L * 86_400_000L,
            lastObservedMillis = atHour(18),
            patternKey = "freq:APP:com.android.chrome",
            entities = mapOf("package" to "com.android.chrome"),
            uniqueDays = 5,
            status = BehaviorCandidateStatus.SUGGEST_ONLY,
        )
        val lowerConfStrong = headphones(0.72f)
        val snap = snapshot(headphones = true, app = "com.miui.home", at = atHour(18))
        val r = engine.evaluateBest(
            listOf(highConfWeak, lowerConfStrong),
            snap,
            ProactivityState.initial(testConfig),
            BehaviorFeedbackView(),
            snap.takenAtMillis,
        )
        // weak package FREQUENCY usually ineligible/low-rel; strong headphones should win if eligible
        if (r.decision.type != ProactiveDecisionType.NO_OP) {
            assertEquals(lowerConfStrong.patternKey, r.decision.behaviorPatternKey)
        } else {
            // if both NO_OP, still must not pick chrome as SELECTED
            assertNotEquals(highConfWeak.patternKey, r.decision.behaviorPatternKey?.takeIf {
                r.decision.reasonCode == ProactiveReasonCode.SELECTED
            })
        }
    }

    @Test
    fun evaluateBestRelevanceFirstAmongEligible() {
        val musicA = "com.spotify.music"
        val musicB = "com.google.android.apps.youtube.music"
        // Same hour/headphones → similar relevance; higher conf wins; tie-break stable
        val a = headphones(0.70f, follow = musicA).copy(
            patternKey = "routine:HEADPHONES→$musicA@18",
        )
        val b = headphones(0.85f, follow = musicB).copy(
            patternKey = "routine:HEADPHONES→$musicB@18",
        )
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val state = ProactivityState.initial(testConfig)
        val r1 = engine.evaluateBest(listOf(a, b), snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        val r2 = engine.evaluateBest(listOf(b, a), snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(r1.decision.behaviorPatternKey, r2.decision.behaviorPatternKey)
        assertEquals(r1.decision.type, r2.decision.type)
        assertEquals(b.patternKey, r1.decision.behaviorPatternKey)
    }

    @Test
    fun evaluateBestStableTieBreakOnEqualScores() {
        val a = headphones(0.80f, follow = "com.pkg.aaa").copy(
            patternKey = "routine:HEADPHONES→com.pkg.aaa@18",
        )
        val b = headphones(0.80f, follow = "com.pkg.zzz").copy(
            patternKey = "routine:HEADPHONES→com.pkg.zzz@18",
        )
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val state = ProactivityState.initial(testConfig)
        val rForward = engine.evaluateBest(listOf(a, b), snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        val rReverse = engine.evaluateBest(listOf(b, a), snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(rForward.decision.behaviorPatternKey, rReverse.decision.behaviorPatternKey)
        // lex smaller patternKey wins on full tie
        assertEquals(a.patternKey, rForward.decision.behaviorPatternKey)
    }

    // --- confidence × relevance matrix ---

    @Test
    fun matrixHighConfHighRelProactive() {
        val c = headphones(0.88f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertNotEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertTrue((r.decision.relevance ?: 0f) >= 0.55f)
    }

    @Test
    fun matrixHighConfLowRelNoOp() {
        val c = headphones(0.91f)
        val snap = snapshot(headphones = false, app = "com.android.settings", at = atHour(3))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
    }

    @Test
    fun matrixLowConfHighRelCautious() {
        val c = headphones(0.42f) // below / near eligible floor
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        // existing policy: below minConfidenceEligible → NO_OP; never CONFIRMATION
        assertNotEquals(ProactiveDecisionType.CONFIRMATION_REQUEST, r.decision.type)
        if (c.confidence < DecisionPolicyThresholds().minConfidenceEligible) {
            assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        }
    }

    @Test
    fun matrixLowConfLowRelNoOp() {
        val c = headphones(0.30f)
        val snap = snapshot(headphones = false, app = "settings", at = atHour(3))
        val r = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
    }

    // --- anti-storm ---

    @Test
    fun rapidTicksDoNotStormBudget() {
        val runtime = ProactiveRuntime(engine = engine, cooldownPolicy = CooldownPolicy(testConfig))
        val c = headphones(0.88f)
        val base = atHour(18)
        var firstSpendTokens = -1
        var engineEvaluationsThatSpent = 0
        repeat(8) { i ->
            val snap = snapshot(headphones = true, app = "launcher", at = base + i * 50L)
            val beforeTokens = runtime.state().budgetTokens
            val beforeGlobal = runtime.state().globalCooldownUntilMillis
            val r = runtime.evaluate(
                snap,
                listOf(c),
                BehaviorFeedbackView(),
                nowMillis = snap.takenAtMillis,
                minIntervalMs = 2_000L,
                force = false,
            )
            val spent = runtime.state().budgetTokens < beforeTokens ||
                runtime.state().globalCooldownUntilMillis > beforeGlobal
            if (spent) {
                engineEvaluationsThatSpent++
                if (firstSpendTokens < 0) firstSpendTokens = r.nextState.budgetTokens
            }
        }
        // coalescing + cooldown: at most one real spend despite burst of observations
        assertTrue("storm spends=$engineEvaluationsThatSpent", engineEvaluationsThatSpent <= 1)
        assertEquals(1, engineEvaluationsThatSpent)
        if (firstSpendTokens >= 0) {
            assertEquals(firstSpendTokens, runtime.state().budgetTokens)
        }
        // UI: only one visible decision
        assertNotNull(runtime.lastVisibleDecision())
        assertNotEquals(ProactiveDecisionType.NO_OP, runtime.lastVisibleDecision()!!.type)
    }

    @Test
    fun noOpDoesNotSpendBudget() {
        val c = headphones(0.9f)
        val snap = snapshot(headphones = false, app = "settings", at = atHour(3))
        val state = ProactivityState.initial(testConfig)
        val before = state.budgetTokens
        val r = engine.evaluate(c, snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertEquals(before, r.nextState.budgetTokens)
    }

    @Test
    fun duplicateDoesNotSpendBudgetAgain() {
        val c = headphones(0.88f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val first = engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis)
        assertNotEquals(ProactiveDecisionType.NO_OP, first.decision.type)
        val tokensAfter = first.nextState.budgetTokens
        val second = engine.evaluate(
            c, snap, first.nextState, BehaviorFeedbackView(), snap.takenAtMillis + 500L,
        )
        assertEquals(ProactiveDecisionType.NO_OP, second.decision.type)
        assertEquals(tokensAfter, second.nextState.budgetTokens)
    }

    @Test
    fun evaluateDoesNotMutateInput() {
        val c = headphones(0.88f)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val state = ProactivityState.initial(testConfig)
        val tokens = state.budgetTokens
        val global = state.globalCooldownUntilMillis
        engine.evaluateBest(listOf(c), snap, state, BehaviorFeedbackView(), snap.takenAtMillis)
        assertEquals(tokens, state.budgetTokens)
        assertEquals(global, state.globalCooldownUntilMillis)
    }

    @Test
    fun fingerprintHasNoRawNotificationOrScreenText() {
        val c = headphones(0.88f)
        val snap = ContextSnapshot(
            context = PhoneContext(
                currentApp = "com.miui.home",
                headphonesConnected = true,
                screenOn = true,
                lastNotificationSummary = "SECRET OTP 123456 please transfer money",
            ),
            takenAtMillis = atHour(18),
        )
        val fp = filter.buildFingerprint(c, snap)
        assertFalse(fp.contains("SECRET"))
        assertFalse(fp.contains("OTP"))
        assertFalse(fp.contains("123456"))
        assertTrue(fp.length <= 160)
        val encoded = ProactivitySnapshotCodec.encode(
            engine.evaluate(c, snap, ProactivityState.initial(testConfig), BehaviorFeedbackView(), snap.takenAtMillis).nextState,
        )
        assertFalse(encoded.contains("SECRET"))
        assertFalse(encoded.contains("OTP"))
    }

    @Test
    fun activeCandidatesIsBoundedInMemoryNotFullHistory() {
        val store = BehaviorStore()
        repeat(50) { i ->
            store.upsertSuggestions(
                listOf(
                    headphones(0.5f + (i % 40) * 0.01f, follow = "pkg.x$i").copy(
                        patternKey = "routine:HEADPHONES→pkg.x$i@18",
                    ),
                ),
            )
        }
        assertTrue(store.activeCandidates().size <= BehaviorStore.ACTIVE_CANDIDATES_LIMIT)
        assertEquals(BehaviorStore.ACTIVE_CANDIDATES_LIMIT, 24)
    }

    private fun headphones(
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

    private fun snapshot(headphones: Boolean, app: String?, at: Long) = ContextSnapshot(
        context = PhoneContext(currentApp = app, headphonesConnected = headphones, screenOn = true),
        takenAtMillis = at,
    )

    private fun atHour(hour: Int): Long {
        val cal = Calendar.getInstance(tz)
        cal.set(2026, Calendar.SEPTEMBER, 21, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
