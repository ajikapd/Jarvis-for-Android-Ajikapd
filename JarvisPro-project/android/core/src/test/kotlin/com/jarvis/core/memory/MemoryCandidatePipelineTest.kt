package com.jarvis.core.memory

import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContext
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryCandidatePipelineTest {

    private lateinit var pipeline: MemoryCandidatePipeline

    @Before
    fun setUp() {
        pipeline = MemoryCandidatePipeline()
        pipeline.resetForTests()
    }

    @Test
    fun routineHintBecomesTemporaryNotAutoStore() {
        val pkg = "com.google.android.youtube"
        val recent = (1..4).map { i ->
            PhoneObservation(
                id = "fg-$i",
                timestampMillis = 1_000L * i,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = pkg,
                confidence = 0.9f,
            )
        }
        val snap = ContextSnapshot(
            context = PhoneContext(currentApp = pkg, currentAppSource = ObservationSource.ACCESSIBILITY),
            takenAtMillis = 5_000L,
        )
        val decisions = pipeline.evaluate(snap, recent)
        val routine = decisions.filter { it.candidate.kind == MemoryKind.ROUTINE }
        assertTrue(routine.isNotEmpty())
        assertTrue(routine.all { it.candidate.provenance == MemoryProvenance.OBSERVED })
        assertTrue(routine.all { it.disposition == MemoryDisposition.TEMPORARY })
        assertFalse(routine.any { it.disposition == MemoryDisposition.STORE })
    }

    @Test
    fun userStatedCanStore() {
        val c = MemoryCandidate(
            kind = MemoryKind.PREFERENCE,
            provenance = MemoryProvenance.USER_STATED,
            statement = "Я обычно вечером читаю Telegram",
            evidenceCount = 1,
            confidence = 0.95f,
            firstSeenMillis = 1L,
            lastSeenMillis = 1L,
            sensitivity = Sensitivity.PRIVATE,
        )
        val d = MemoryPolicy().decide(c)
        assertEquals(MemoryDisposition.STORE, d.disposition)
        assertEquals(MemoryProvenance.USER_STATED, d.candidate.provenance)
    }

    @Test
    fun observedAndUserStatedNotMixed() {
        val observed = MemoryCandidate(
            kind = MemoryKind.ROUTINE,
            provenance = MemoryProvenance.OBSERVED,
            statement = "часто YouTube",
            evidenceCount = 4,
            confidence = 0.8f,
            firstSeenMillis = 1L,
            lastSeenMillis = 2L,
            sensitivity = Sensitivity.PRIVATE,
        )
        val stated = observed.copy(
            provenance = MemoryProvenance.USER_STATED,
            confidence = 0.95f,
        )
        assertEquals(MemoryDisposition.TEMPORARY, MemoryPolicy().decide(observed).disposition)
        assertEquals(MemoryDisposition.STORE, MemoryPolicy().decide(stated).disposition)
    }

    @Test
    fun secretRejectedBySensitivityFilter() {
        val c = MemoryCandidate(
            kind = MemoryKind.EPISODIC,
            provenance = MemoryProvenance.OBSERVED,
            statement = "secret",
            evidenceCount = 1,
            confidence = 0.9f,
            firstSeenMillis = 1L,
            lastSeenMillis = 1L,
            sensitivity = Sensitivity.SECRET,
        )
        val early = SensitivityFilter().filter(c)
        assertEquals(MemoryDisposition.REJECT, early!!.disposition)
    }

    @Test
    fun insufficientEvidenceNoRoutine() {
        val pkg = "com.android.chrome"
        val recent = listOf(
            PhoneObservation(
                id = "1",
                timestampMillis = 100L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = pkg,
            ),
        )
        val snap = ContextSnapshot(
            context = PhoneContext(currentApp = pkg),
            takenAtMillis = 200L,
        )
        val candidates = MemoryCandidateDetector().detect(snap, recent)
        assertTrue(candidates.none { it.kind == MemoryKind.ROUTINE })
    }
}
