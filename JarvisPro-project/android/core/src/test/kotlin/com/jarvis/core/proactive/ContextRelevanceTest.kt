package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorCandidateStatus
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.behavior.PatternFamily
import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Stage 7 A→B: ContextMatcher + RelevanceEvaluator на чистых данных.
 * Без PhoneContextBus / UI / ToolExecutor.
 */
class ContextRelevanceTest {

    private val tz = TimeZone.getTimeZone("UTC")
    private val matcher = ContextMatcher(timeZone = { tz })
    private val relevance = RelevanceEvaluator(matcher = matcher)

    @Test
    fun headphonesMatchWhenConnectedAndMusicNotOpen() {
        val c = headphonesMusic(hour = 18)
        val snap = snapshot(
            headphones = true,
            app = "com.miui.home",
            at = atHour(18),
        )
        val m = matcher.match(c, snap)
        assertTrue(m.matched)
        assertFalse(m.alreadySatisfied)
        assertTrue(m.matchedSignals.any { it.startsWith("headphones") })
        assertTrue(m.score >= 0.5f)
    }

    @Test
    fun headphonesMismatchWhenNotConnected() {
        val c = headphonesMusic(hour = 18)
        val snap = snapshot(headphones = false, app = "com.miui.home", at = atHour(18))
        val m = matcher.match(c, snap)
        assertFalse(m.matched)
        assertTrue(m.mismatchSignals.contains("headphones"))
    }

    @Test
    fun alreadySatisfiedWhenMusicOpen() {
        val music = "com.google.android.apps.youtube.music"
        val c = headphonesMusic(hour = 18, follow = music)
        val snap = snapshot(headphones = true, app = music, at = atHour(18))
        val m = matcher.match(c, snap)
        assertTrue(m.alreadySatisfied)
        assertFalse(m.matched)
        val r = relevance.evaluate(c, snap)
        assertEquals(0f, r.relevance, 0.001f)
        assertEquals(ProactiveReasonCode.CONTEXT_ALREADY_SATISFIED, r.reasonCode)
    }

    @Test
    fun timeMatchWithinTolerance() {
        val c = headphonesMusic(hour = 18)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(19))
        val m = matcher.match(c, snap)
        assertTrue(m.matchedSignals.any { it.startsWith("hour~") })
        assertTrue(m.score > 0.4f)
    }

    @Test
    fun timeMismatchFarFromHour() {
        val c = headphonesMusic(hour = 18)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(3))
        val m = matcher.match(c, snap)
        assertTrue(m.mismatchSignals.any { it.startsWith("hour~") })
        // headphones still match, but hour mismatch lowers score / may still match overall
        assertTrue(m.matchedSignals.contains("headphones"))
    }

    @Test
    fun calculatorContextLowRelevanceForHeadphonesHabit() {
        val c = headphonesMusic(hour = 18)
        val snap = snapshot(
            headphones = false,
            app = "com.google.android.calculator",
            at = atHour(18),
        )
        val r = relevance.evaluate(c, snap)
        assertTrue(r.relevance < 0.45f)
        assertFalse(r.match.matched)
    }

    @Test
    fun highConfidenceDoesNotEqualHighRelevance() {
        val c = headphonesMusic(hour = 18).copy(confidence = 0.91f)
        val snap = snapshot(
            headphones = false,
            app = "com.android.settings",
            at = atHour(3),
        )
        val r = relevance.evaluate(c, snap)
        assertTrue(c.confidence >= 0.90f)
        assertTrue(r.relevance < 0.50f)
    }

    @Test
    fun mediumConfidenceHighRelevanceWhenContextFits() {
        val c = headphonesMusic(hour = 18).copy(confidence = 0.76f)
        val snap = snapshot(headphones = true, app = "com.miui.home", at = atHour(18))
        val r = relevance.evaluate(c, snap)
        assertTrue(r.match.matched)
        assertTrue(r.relevance >= 0.55f)
        // confidence и relevance — разные числа
        assertTrue(kotlin.math.abs(r.relevance - c.confidence) > 0.01f || r.relevance != c.confidence)
    }

    @Test
    fun explainableSignalsPresent() {
        val c = headphonesMusic(hour = 18)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val m = matcher.match(c, snap)
        assertTrue(m.matchedSignals.isNotEmpty())
        assertTrue(m.score in 0f..1f)
    }

    @Test
    fun staleCandidateZeroRelevance() {
        val c = headphonesMusic(hour = 18).copy(status = BehaviorCandidateStatus.STALE)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = relevance.evaluate(c, snap)
        assertEquals(0f, r.relevance, 0.001f)
        assertEquals(ProactiveReasonCode.STALE, r.reasonCode)
    }

    @Test
    fun disabledCandidateZeroRelevance() {
        val c = headphonesMusic(hour = 18).copy(status = BehaviorCandidateStatus.DISABLED)
        val snap = snapshot(headphones = true, app = "launcher", at = atHour(18))
        val r = relevance.evaluate(c, snap)
        assertEquals(ProactiveReasonCode.DISABLED, r.reasonCode)
    }

    @Test
    fun customWeightsChangeScore() {
        val c = headphonesMusic(hour = 18)
        // mismatch: headphones off → contextPart низкий
        val snap = snapshot(headphones = false, app = "launcher", at = atHour(18))
        val contextHeavy = RelevanceEvaluator(
            matcher = matcher,
            weights = RelevanceWeights(contextMatch = 0.85f, recency = 0.05f, statusBoost = 0.05f, kindBoost = 0.05f),
        )
        val recencyHeavy = RelevanceEvaluator(
            matcher = matcher,
            weights = RelevanceWeights(contextMatch = 0.05f, recency = 0.85f, statusBoost = 0.05f, kindBoost = 0.05f),
        )
        val a = contextHeavy.evaluate(c, snap).relevance
        val b = recencyHeavy.evaluate(c, snap).relevance
        // При плохом context context-heavy должен быть заметно ниже
        assertTrue("contextHeavy=$a recencyHeavy=$b", b > a)
    }

    @Test
    fun temporalFamilyUsesHourEntityOnly() {
        val c = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.TEMPORAL,
            description = "evening chrome",
            confidence = 0.7f,
            evidenceCount = 5,
            firstObservedMillis = atHour(21) - 5L * 86_400_000L,
            lastObservedMillis = atHour(21),
            patternKey = "temporal:21:com.android.chrome",
            entities = mapOf("hour" to "21", "package" to "com.android.chrome"),
        )
        val good = matcher.match(c, snapshot(app = "com.miui.home", at = atHour(21)))
        val bad = matcher.match(c, snapshot(app = "com.miui.home", at = atHour(9)))
        assertTrue(good.matchedSignals.any { it.startsWith("hour~") })
        assertTrue(bad.mismatchSignals.any { it.startsWith("hour~") })
    }

    private fun headphonesMusic(
        hour: Int?,
        follow: String = "com.google.android.apps.youtube.music",
    ): BehaviorCandidate {
        val entities = mutableMapOf(
            "trigger" to "HEADPHONES_CONNECTED",
            "follow" to follow,
        )
        if (hour != null) entities["hour"] = hour.toString()
        return BehaviorCandidate(
            kind = BehaviorKind.ROUTINE,
            family = PatternFamily.SEQUENCE,
            description = "После наушников Music",
            confidence = 0.84f,
            evidenceCount = 5,
            firstObservedMillis = atHour(hour ?: 18) - 5L * 86_400_000L,
            lastObservedMillis = atHour(hour ?: 18),
            patternKey = "routine:HEADPHONES→$follow@${hour ?: 18}",
            entities = entities,
            uniqueDays = 5,
            why = "test",
        )
    }

    private fun snapshot(
        headphones: Boolean = false,
        app: String? = null,
        at: Long = System.currentTimeMillis(),
        battery: Int? = null,
    ): ContextSnapshot = ContextSnapshot(
        context = PhoneContext(
            currentApp = app,
            headphonesConnected = headphones,
            batteryPercent = battery,
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
