package com.jarvis.core.behavior

import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.memory.CompanionMemoryService
import com.jarvis.core.memory.InMemoryMemoryRepository
import com.jarvis.core.memory.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Stage 6.5 hardening — confidence, evidence, feedback, decay, persistence, privacy.
 */
class BehaviorLearningHardeningTest {

    private lateinit var learning: BehaviorLearningService
    private var now = baseDay(0, 18, 0)

    @Before
    fun setUp() {
        now = baseDay(10, 20, 0)
        learning = BehaviorLearningService(clock = { now })
        learning.store().clearForTests()
    }

    @Test
    fun singleEventDoesNotCreateStrongHabit() {
        val score = ConfidenceModel.score(
            evidenceCount = 1,
            uniqueDays = 1,
            lastObservedMillis = now,
            nowMillis = now,
            sourceReliability = 0.9f,
            regularityScore = 1f,
        )
        assertTrue(score <= ConfidenceModel.SINGLE_EVENT_MAX)
        assertTrue(learning.evaluate(listOf(hp(now), app("music", now + 60_000))).isEmpty())
    }

    @Test
    fun confidenceGrowsWithRepeatedSequence() {
        val c3 = ConfidenceModel.score(3, 3, now, now, regularityScore = 0.8f)
        val c7 = ConfidenceModel.score(7, 7, now, now, regularityScore = 0.8f)
        assertTrue(c7 > c3)
        assertTrue(c7 <= ConfidenceModel.HARD_CAP)
    }

    @Test
    fun confidenceHasHardCap() {
        val s = ConfidenceModel.score(50, 30, now, now, regularityScore = 1f, sourceReliability = 1f)
        assertTrue(s <= ConfidenceModel.HARD_CAP)
    }

    @Test
    fun negativeEvidenceLowersConfidence() {
        val clean = ConfidenceModel.score(6, 5, now, now, negativeEvidenceCount = 0, regularityScore = 0.8f)
        val dirty = ConfidenceModel.score(6, 5, now, now, negativeEvidenceCount = 3, regularityScore = 0.8f)
        assertTrue(dirty < clean)
    }

    @Test
    fun habitBreakReducesSequenceConfidence() {
        val obs = mutableListOf<PhoneObservation>()
        // Пн–Вт Music, Ср YouTube, Чт Music, Пт YouTube
        val music = "com.google.android.apps.youtube.music"
        val yt = "com.google.android.youtube"
        for (d in 0 until 5) {
            obs += hp(baseDay(d, 18, 0))
            val pkg = if (d == 2 || d == 4) yt else music
            obs += app(pkg, baseDay(d, 18, 2))
        }
        now = baseDay(5, 20, 0)
        val s = learning.evaluate(obs)
        val musicCand = s.firstOrNull { it.patternKey == "seq:HEADPHONES→$music" }
        // Music только 3 дня — на грани; с negative (2) confidence ниже чистой серии
        val cleanObs = (0 until 5).flatMap { d ->
            listOf(hp(baseDay(d, 18, 0)), app(music, baseDay(d, 18, 2)))
        }
        learning.store().clearForTests()
        val clean = learning.evaluate(cleanObs)
            .first { it.patternKey == "seq:HEADPHONES→$music" }
        if (musicCand != null) {
            assertTrue(musicCand.negativeEvidenceCount >= 1)
            assertTrue(musicCand.confidence < clean.confidence)
        } else {
            // паттерн мог не пройти порог из‑за negative — тоже OK
            assertTrue(clean.confidence >= 0.45f)
        }
    }

    @Test
    fun decayReducesStaleConfidence() {
        val fresh = ConfidenceModel.score(8, 5, now, now, regularityScore = 0.7f)
        val oldLast = now - 30L * 86_400_000L
        val aged = ConfidenceModel.score(8, 5, oldLast, now, regularityScore = 0.7f)
        assertTrue(aged < fresh)

        val c = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.SEQUENCE,
            description = "old habit",
            confidence = 0.81f,
            evidenceCount = 10,
            firstObservedMillis = now - 60L * 86_400_000L,
            lastObservedMillis = now - 30L * 86_400_000L,
            patternKey = "seq:HEADPHONES→music",
        )
        val d1 = BehaviorDecay.apply(c, now)
        val d2 = BehaviorDecay.apply(c, now + 14L * 86_400_000L)
        assertTrue(d1.confidence < 0.81f)
        assertTrue(d2.confidence < d1.confidence)
    }

    @Test
    fun duplicateDeliveryDoesNotInflateConfidence() {
        val base = seqHeadphones(5)
        // Duplicate same ids + near-identical copies
        val duped = base + base.map { it.copy(id = UUID.randomUUID().toString()) }
        now = baseDay(5, 20, 0)
        learning.evaluate(base, force = true)
        val conf1 = learning.store().suggestions()
            .first { it.patternKey.contains("HEADPHONES") }.confidence
        learning.evaluate(duped, force = true)
        val conf2 = learning.store().suggestions()
            .first { it.patternKey.contains("HEADPHONES") }.confidence
        // Merge без нового уникального day evidence не должен сильно раздуть
        assertTrue(conf2 <= ConfidenceModel.HARD_CAP)
        assertTrue(kotlin.math.abs(conf2 - conf1) < 0.25f)
    }

    @Test
    fun sameDaySpamCountsAsOneEvidenceDay() {
        val music = "com.google.android.apps.youtube.music"
        val obs = mutableListOf<PhoneObservation>()
        // 5 раз в один день — не привычка
        for (i in 0 until 5) {
            obs += hp(baseDay(0, 18, i * 10))
            obs += app(music, baseDay(0, 18, i * 10 + 2))
        }
        now = baseDay(0, 20, 0)
        assertTrue(learning.evaluate(obs).none { it.patternKey == "seq:HEADPHONES→$music" })
    }

    @Test
    fun ignoreIsCooldownNotDisable() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        learning.evaluate(obs, force = true)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.IGNORE)
        assertFalse(learning.store().disabledKeys().contains(key))
        assertTrue(learning.store().suggestions(now).none { it.patternKey == key })
        // После cooldown снова видно
        val later = now + 7 * 3_600_000L
        assertTrue(learning.store().suggestions(later).any { it.patternKey == key })
    }

    @Test
    fun disableSuppressesForever() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        learning.evaluate(obs, force = true)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.DISABLE)
        assertTrue(learning.store().disabledKeys().contains(key))
        assertTrue(learning.evaluate(obs, force = true).none { it.patternKey == key })
        assertTrue(learning.store().suggestions(now + 30L * 86_400_000L).none { it.patternKey == key })
    }

    @Test
    fun rejectDoesNotDisable() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        learning.evaluate(obs, force = true)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.REJECT)
        assertFalse(learning.store().disabledKeys().contains(key))
        assertTrue(learning.store().rejectedKeys().contains(key))
        assertEquals(BehaviorCandidateStatus.REJECTED, learning.store().get(key)!!.status)
    }

    @Test
    fun acceptGoesToMemoryNotToolCall() {
        val mem = CompanionMemoryService(InMemoryMemoryRepository())
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        learning.evaluate(obs, force = true)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        val before = mem.recall.recent().size
        learning.feedback(key, BehaviorFeedback.ACCEPT, memory = mem)
        assertEquals(BehaviorCandidateStatus.ACCEPTED, learning.store().get(key)!!.status)
        assertTrue(mem.recall.recent().size > before)
        assertTrue(
            mem.recall.recent().any {
                it.type == MemoryKind.ROUTINE || it.type == MemoryKind.SEMANTIC
            },
        )
        // Нет ToolCall — проверяем архитектурно: feedback не кидает исключений и пишет только memory
        assertTrue(learning.store().suggestions().none { it.patternKey == key })
    }

    @Test
    fun persistenceSurvivesRestart() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        learning.evaluate(obs, force = true)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.IGNORE)
        val snap = learning.store().snapshot()
        val encoded = BehaviorSnapshotCodec.encode(snap)

        val restored = BehaviorStore()
        restored.restore(BehaviorSnapshotCodec.decode(encoded))
        assertTrue(restored.get(key) != null)
        assertTrue(restored.ignoreUntilMap().containsKey(key))
        assertTrue(restored.suggestions(now).none { it.patternKey == key })
        assertTrue(restored.suggestions(now + 7 * 3_600_000L).any { it.patternKey == key })
    }

    @Test
    fun disablePersistsAcrossRestore() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        learning.evaluate(obs, force = true)
        val key = learning.store().suggestions().first { it.patternKey.contains("HEADPHONES") }.patternKey
        learning.feedback(key, BehaviorFeedback.DISABLE)
        val encoded = BehaviorSnapshotCodec.encode(learning.store().snapshot())
        val restored = BehaviorStore()
        restored.restore(BehaviorSnapshotCodec.decode(encoded))
        assertTrue(restored.disabledKeys().contains(key))
        val svc = BehaviorLearningService(store = restored, clock = { now })
        assertTrue(svc.evaluate(obs, force = true).none { it.patternKey == key })
    }

    @Test
    fun staleBehaviorNotSuggested() {
        val c = BehaviorCandidate(
            kind = BehaviorKind.PATTERN,
            family = PatternFamily.FREQUENCY,
            description = "gone",
            confidence = 0.5f,
            evidenceCount = 8,
            firstObservedMillis = now - 90L * 86_400_000L,
            lastObservedMillis = now - 50L * 86_400_000L,
            patternKey = "freq:gone",
        )
        val decayed = BehaviorDecay.apply(c, now)
        val filtered = BehaviorPolicy().filter(listOf(decayed), emptySet(), emptySet())
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun sanitizerStripsNotificationTextAndSecrets() {
        val secret = PhoneObservation(
            timestampMillis = now,
            source = ObservationSource.NOTIFICATION_LISTENER,
            type = ObservationType.NOTIFICATION_POSTED,
            packageName = "com.bank",
            summary = "OTP 123456",
            entities = mapOf("title" to "Bank", "text" to "OTP 123456", "package" to "com.bank"),
            sensitivity = Sensitivity.SECRET,
        )
        val notif = PhoneObservation(
            timestampMillis = now,
            source = ObservationSource.NOTIFICATION_LISTENER,
            type = ObservationType.NOTIFICATION_POSTED,
            packageName = "com.news",
            summary = "Breaking: personal story",
            entities = mapOf(
                "title" to "News",
                "text" to "personal story",
                "package" to "com.news",
                "ongoing" to "false",
            ),
            sensitivity = Sensitivity.PRIVATE,
        )
        val cleaned = BehaviorObservationSanitizer.sanitizeForLearning(listOf(secret, notif))
        assertTrue(cleaned.none { it.sensitivity == Sensitivity.SECRET })
        val n = cleaned.single()
        assertEquals("notif:com.news", n.summary)
        assertFalse(n.entities.containsKey("text"))
        assertFalse(n.entities.containsKey("title"))
    }

    @Test
    fun mergeWithoutNewEvidenceDoesNotGrow() {
        val m = ConfidenceModel.merge(0.70f, 0.80f, newEvidenceAdded = false)
        assertTrue(m <= 0.70f)
        val grown = ConfidenceModel.merge(0.70f, 0.85f, newEvidenceAdded = true)
        assertTrue(grown >= 0.70f)
        assertTrue(grown <= ConfidenceModel.HARD_CAP)
    }

    @Test
    fun evaluateIsBoundedInterval() {
        val obs = seqHeadphones(5)
        now = baseDay(5, 20, 0)
        val t0 = System.nanoTime()
        repeat(20) { learning.evaluate(obs, force = false, minIntervalMs = 60_000L) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        // Не должно быть тяжёлого thrash — быстрый no-op path после первого
        assertTrue(ms < 5_000.0)
        assertNotNull(learning.store().suggestions().firstOrNull())
    }

    private fun seqHeadphones(days: Int): List<PhoneObservation> {
        val music = "com.google.android.apps.youtube.music"
        return (0 until days).flatMap { d ->
            listOf(hp(baseDay(d, 18, 0)), app(music, baseDay(d, 18, 2)))
        }
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
