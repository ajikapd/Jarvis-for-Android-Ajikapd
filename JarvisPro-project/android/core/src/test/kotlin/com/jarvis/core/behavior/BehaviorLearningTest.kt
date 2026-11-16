package com.jarvis.core.behavior

import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.memory.CompanionMemoryService
import com.jarvis.core.memory.InMemoryMemoryRepository
import com.jarvis.core.memory.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BehaviorLearningTest {

    private lateinit var learning: BehaviorLearningService
    private var now = baseDay(0, 18, 0)

    @Before
    fun setUp() {
        now = baseDay(0, 18, 0)
        learning = BehaviorLearningService(clock = { now })
        learning.store().clearForTests()
    }

    @Test
    fun insufficientEvidenceNoSuggestion() {
        val obs = listOf(
            hp(baseDay(0, 18, 0)),
            app("yt", baseDay(0, 18, 2)),
        )
        assertTrue(learning.evaluate(obs).isEmpty())
    }

    @Test
    fun sequenceHeadphonesToApp() {
        val obs = mutableListOf<PhoneObservation>()
        for (d in 0 until 4) {
            obs += hp(baseDay(d, 18, 0))
            obs += app("com.google.android.apps.youtube.music", baseDay(d, 18, 2))
        }
        now = baseDay(4, 20, 0)
        val suggestions = learning.evaluate(obs)
        assertTrue(suggestions.any { it.family == PatternFamily.SEQUENCE && it.patternKey.contains("HEADPHONES") })
        val c = suggestions.first { it.patternKey.contains("HEADPHONES") }
        assertTrue(c.evidenceCount >= 3)
        assertTrue(c.confidence >= 0.45f)
        assertEquals(BehaviorCandidateStatus.SUGGEST_ONLY, c.status)
    }

    @Test
    fun frequencyPattern() {
        val obs = (0 until 8).map { i ->
            app("com.android.chrome", baseDay(i % 4, 10 + (i % 3), 0))
        }
        now = baseDay(5, 0, 0)
        val s = learning.evaluate(obs)
        assertTrue(s.any { it.family == PatternFamily.FREQUENCY && it.patternKey.contains("chrome") })
    }

    @Test
    fun temporalPattern() {
        val obs = (0 until 5).map { d ->
            app("org.telegram.messenger", baseDay(d, 21, 5))
        }
        now = baseDay(5, 0, 0)
        val s = learning.evaluate(obs)
        assertTrue(s.any { it.family == PatternFamily.TEMPORAL })
    }

    @Test
    fun coOccurrence() {
        val obs = mutableListOf<PhoneObservation>()
        for (d in 0 until 5) {
            obs += app("com.spotify.music", baseDay(d, 12, 0))
            obs += app("com.android.chrome", baseDay(d, 12, 1))
        }
        now = baseDay(4, 12, 30)
        val s = learning.evaluate(obs, force = true)
        assertTrue(s.any { it.family == PatternFamily.CO_OCCURRENCE })
    }

    @Test
    fun routineRequiresMoreDaysThanPattern() {
        // 3 days only — enough for pattern minEvidence=3 but routine needs 4 evidence + 3 days
        val obs = mutableListOf<PhoneObservation>()
        for (d in 0 until 3) {
            obs += hp(baseDay(d, 18, 0))
            obs += app("yt.music", baseDay(d, 18, 3))
        }
        val window = ObservationWindow.from(obs, nowMillis = baseDay(3, 0, 0))
        val patterns = PatternDetector().detect(window)
        val routines = RoutineDetector().detect(window)
        assertTrue(patterns.any { it.patternKey.contains("HEADPHONES") })
        assertTrue(routines.none { it.patternKey.contains("HEADPHONES") })
    }

    @Test
    fun routineAppearsWithEnoughDays() {
        val obs = mutableListOf<PhoneObservation>()
        for (d in 0 until 5) {
            obs += hp(baseDay(d, 18, 0))
            obs += app("yt.music", baseDay(d, 18, 2))
        }
        now = baseDay(5, 0, 0)
        val s = learning.evaluate(obs)
        assertTrue(s.any { it.kind == BehaviorKind.ROUTINE })
    }

    @Test
    fun confidenceIncreasesWithEvidence() {
        val low = ConfidenceModel.score(2, 1, now, now, regularityScore = 0.5f)
        val high = ConfidenceModel.score(15, 7, now, now, regularityScore = 0.8f)
        assertTrue(high > low)
    }

    @Test
    fun confidenceDecayOverTime() {
        val c = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "x",
            confidence = 0.9f,
            evidenceCount = 10,
            firstObservedMillis = now - 40L * 86_400_000L,
            lastObservedMillis = now - 30L * 86_400_000L,
            patternKey = "freq:x",
        )
        val decayed = BehaviorDecay.apply(c, now)
        assertTrue(decayed.confidence < c.confidence)
        assertTrue(decayed.status == BehaviorCandidateStatus.STALE || decayed.confidence < 0.9f)
    }

    @Test
    fun negativeEvidenceLowersViaReject() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 0, 0)
        learning.evaluate(obs)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.REJECT)
        assertTrue(learning.store().rejectedKeys().contains(key))
        val after = learning.store().get(key)
        assertTrue(after!!.status == BehaviorCandidateStatus.REJECTED)
        assertTrue(after.confidence < 0.95f)
    }

    @Test
    fun acceptIgnoreRejectDisableAreDistinct() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 0, 0)
        learning.evaluate(obs)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey

        // IGNORE = cooldown, не DISABLED
        learning.feedback(key, BehaviorFeedback.IGNORE)
        assertFalse(learning.store().disabledKeys().contains(key))
        assertTrue(learning.store().suggestions(now).none { it.patternKey == key })

        learning.store().clearForTests()
        learning.evaluate(obs)
        val k2 = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        assertEquals(BehaviorCandidateStatus.REJECTED, learning.feedback(k2, BehaviorFeedback.REJECT)!!.status)

        learning.store().clearForTests()
        learning.evaluate(obs)
        val k3 = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        assertEquals(BehaviorCandidateStatus.DISABLED, learning.feedback(k3, BehaviorFeedback.DISABLE)!!.status)
        assertTrue(learning.evaluate(obs).none { it.patternKey == k3 })
    }

    @Test
    fun acceptWritesMemoryNotDirectRepositoryFromDetector() {
        val mem = CompanionMemoryService(InMemoryMemoryRepository())
        val obs = seqHeadphones(5)
        now = baseDay(5, 0, 0)
        learning.evaluate(obs)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.ACCEPT, memory = mem)
        assertTrue(mem.recall.recent().any { it.type == MemoryKind.ROUTINE || it.type == MemoryKind.SEMANTIC })
        assertEquals(BehaviorCandidateStatus.ACCEPTED, learning.store().get(key)!!.status)
    }

    @Test
    fun duplicateEvidenceDoesNotExplodeKeys() {
        val obs = seqHeadphones(6)
        now = baseDay(6, 0, 0)
        learning.evaluate(obs)
        learning.evaluate(obs)
        val keys = learning.store().suggestions().map { it.patternKey }.filter { it.startsWith("seq:HEADPHONES") }
        assertEquals(1, keys.distinct().size)
    }

    @Test
    fun stalePatternFilteredWhenDecayedBelowThreshold() {
        val c = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "old",
            confidence = 0.5f,
            evidenceCount = 5,
            firstObservedMillis = now - 60L * 86_400_000L,
            lastObservedMillis = now - 45L * 86_400_000L,
            patternKey = "freq:old",
        )
        val decayed = BehaviorDecay.apply(c, now)
        val filtered = BehaviorPolicy().filter(listOf(decayed), emptySet(), emptySet())
        assertTrue(filtered.isEmpty() || filtered.first().confidence >= 0.45f)
        if (decayed.status == BehaviorCandidateStatus.STALE && decayed.confidence < 0.45f) {
            assertTrue(filtered.isEmpty())
        }
    }

    private fun seqHeadphones(days: Int, dayOffset: Int = 0): List<PhoneObservation> {
        val out = mutableListOf<PhoneObservation>()
        for (d in 0 until days) {
            out += hp(baseDay(d + dayOffset, 18, 0))
            out += app("com.google.android.apps.youtube.music", baseDay(d + dayOffset, 18, 2))
        }
        return out
    }

    private fun hp(ts: Long) = PhoneObservation(
        timestampMillis = ts,
        source = ObservationSource.SYSTEM_BROADCAST,
        type = ObservationType.HEADPHONES_CONNECTED,
        summary = "hp",
        confidence = 1f,
    )

    private fun app(pkg: String, ts: Long) = PhoneObservation(
        timestampMillis = ts,
        source = ObservationSource.ACCESSIBILITY,
        type = ObservationType.APP_FOREGROUND,
        packageName = pkg,
        summary = pkg,
        confidence = 0.9f,
    )

    private fun baseDay(dayOffset: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.SEPTEMBER, 1 + dayOffset, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
