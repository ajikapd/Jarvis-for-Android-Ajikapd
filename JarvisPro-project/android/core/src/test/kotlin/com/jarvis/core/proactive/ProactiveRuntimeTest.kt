package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorCandidateStatus
import com.jarvis.core.behavior.BehaviorFeedback
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.behavior.BehaviorLearningService
import com.jarvis.core.behavior.BehaviorStore
import com.jarvis.core.behavior.PatternFamily
import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Stage 7E: ProactiveRuntime (чистый слой под CompanionProactive).
 */
class ProactiveRuntimeTest {

    private val tz = TimeZone.getTimeZone("UTC")
    private lateinit var runtime: ProactiveRuntime
    private lateinit var store: BehaviorStore
    private lateinit var learning: BehaviorLearningService

    @Before
    fun setUp() {
        runtime = ProactiveRuntime()
        store = BehaviorStore()
        learning = BehaviorLearningService(store = store)
    }

    @Test
    fun noOpDoesNotBecomeVisible() {
        val c = headphones(0.9f)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = false, app = "settings", at = atHour(3))
        val r = runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        assertEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertNull(runtime.lastVisibleDecision())
    }

    @Test
    fun confirmationBecomesVisible() {
        val c = headphones(0.88f)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = true, app = "com.miui.home", at = atHour(18))
        val r = runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        assertNotEquals(ProactiveDecisionType.NO_OP, r.decision.type)
        assertNotNull(runtime.lastVisibleDecision())
        assertEquals(r.decision.type, runtime.lastVisibleDecision()!!.type)
    }

    @Test
    fun ignoreViaLearningClearsVisibleAndBlocks() {
        val c = headphones(0.88f)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val first = runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        assertNotEquals(ProactiveDecisionType.NO_OP, first.decision.type)
        val key = first.decision.behaviorPatternKey!!
        learning.feedback(key, BehaviorFeedback.IGNORE)
        runtime.afterFeedback(key, BehaviorFeedback.IGNORE, snap.takenAtMillis)
        assertNull(runtime.lastVisibleDecision())
        val second = runtime.evaluate(
            snap,
            store.activeCandidates(),
            BehaviorFeedbackView.from(store),
            nowMillis = snap.takenAtMillis + 60_000L,
            force = true,
        )
        assertEquals(ProactiveDecisionType.NO_OP, second.decision.type)
        assertEquals(ProactiveReasonCode.FEEDBACK_COOLDOWN, second.decision.reasonCode)
    }

    @Test
    fun disableBlocksForever() {
        val c = headphones(0.88f)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val first = runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        val key = first.decision.behaviorPatternKey!!
        learning.feedback(key, BehaviorFeedback.DISABLE)
        runtime.afterFeedback(key, BehaviorFeedback.DISABLE, snap.takenAtMillis)
        val second = runtime.evaluate(
            snap,
            store.activeCandidates(snap.takenAtMillis + 1),
            BehaviorFeedbackView.from(store),
            nowMillis = snap.takenAtMillis + 3_600_000L,
            force = true,
        )
        assertEquals(ProactiveDecisionType.NO_OP, second.decision.type)
        assertTrue(
            second.decision.reasonCode == ProactiveReasonCode.DISABLED ||
                second.decision.reasonCode == ProactiveReasonCode.NO_CANDIDATE,
        )
    }

    @Test
    fun acceptDoesNotExecuteAndClearsVisible() {
        val c = headphones(0.88f)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val first = runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        val key = first.decision.behaviorPatternKey!!
        learning.feedback(key, BehaviorFeedback.ACCEPT)
        runtime.afterFeedback(key, BehaviorFeedback.ACCEPT, snap.takenAtMillis)
        assertNull(runtime.lastVisibleDecision())
        assertEquals(BehaviorCandidateStatus.ACCEPTED, store.get(key)?.status)
    }

    @Test
    fun stateRestoreRoundTrip() {
        val c = headphones(0.88f)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        val encoded = ProactivitySnapshotCodec.encode(r.nextState)
        val restored = ProactiveRuntime()
        restored.restore(ProactivitySnapshotCodec.decode(encoded))
        assertTrue(restored.state().globalCooldownUntilMillis > 0L || r.decision.type == ProactiveDecisionType.NO_OP)
    }

    @Test
    fun corruptedPersistenceSafe() {
        val restored = ProactiveRuntime()
        restored.restore(ProactivitySnapshotCodec.decode("garbage"))
        assertEquals(ProactivityState.initial().budgetTokens, restored.state().budgetTokens)
    }

    @Test
    fun alreadySatisfiedNoVisible() {
        val music = "com.google.android.apps.youtube.music"
        val c = headphones(0.9f, follow = music)
        store.upsertSuggestions(listOf(c))
        val snap = snapshot(headphones = true, app = music, at = atHour(18))
        runtime.evaluate(snap, store.activeCandidates(), BehaviorFeedbackView.from(store), force = true)
        assertNull(runtime.lastVisibleDecision())
    }

    @Test
    fun activeCandidatesBounded() {
        repeat(40) { i ->
            store.upsertSuggestions(
                listOf(
                    headphones(0.5f + (i % 40) * 0.01f, follow = "pkg.a$i").copy(
                        patternKey = "routine:HEADPHONES→pkg.a$i@18",
                    ),
                ),
            )
        }
        assertTrue(store.activeCandidates().size <= 24)
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?) {
        assertFalse("expected not $unexpected", unexpected == actual)
    }

    private fun headphones(
        confidence: Float,
        follow: String = "com.google.android.apps.youtube.music",
    ): BehaviorCandidate = BehaviorCandidate(
        kind = BehaviorKind.ROUTINE,
        family = PatternFamily.SEQUENCE,
        description = "После наушников Music",
        confidence = confidence,
        evidenceCount = 5,
        firstObservedMillis = atHour(18) - 5L * 86_400_000L,
        lastObservedMillis = atHour(18),
        patternKey = "routine:HEADPHONES→$follow@18",
        entities = mapOf(
            "trigger" to "HEADPHONES_CONNECTED",
            "follow" to follow,
            "hour" to "18",
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
