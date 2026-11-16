package com.jarvis.core.memory

import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContext
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DurableMemoryTest {

    private lateinit var repo: InMemoryMemoryRepository
    private lateinit var writer: MemoryWriter
    private lateinit var recall: MemoryRecall
    private lateinit var service: CompanionMemoryService
    private var now = 1_000_000L

    @Before
    fun setUp() {
        now = 1_000_000L
        repo = InMemoryMemoryRepository()
        writer = MemoryWriter(repo) { now }
        recall = MemoryRecall(repo)
        service = CompanionMemoryService(
            repository = repo,
            writer = writer,
            recall = recall,
        )
    }

    @Test
    fun rejectDoesNotStore() {
        val c = MemoryCandidate(
            kind = MemoryKind.EPISODIC,
            provenance = MemoryProvenance.OBSERVED,
            statement = "noise",
            evidenceCount = 1,
            confidence = 0.1f,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = Sensitivity.NORMAL,
        )
        val d = MemoryPolicy().decide(c)
        assertEquals(MemoryDisposition.REJECT, d.disposition)
        assertNull(writer.persist(d))
        assertTrue(recall.recent().isEmpty())
    }

    @Test
    fun secretRejected() {
        val c = MemoryCandidate(
            kind = MemoryKind.EPISODIC,
            provenance = MemoryProvenance.USER_STATED,
            statement = "pin 1234",
            evidenceCount = 1,
            confidence = 1f,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = Sensitivity.SECRET,
        )
        val early = SensitivityFilter().filter(c)!!
        assertEquals(MemoryDisposition.REJECT, early.disposition)
        assertNull(writer.persist(early))
    }

    @Test
    fun temporaryObservedStoredWithTtl() {
        val c = MemoryCandidate(
            kind = MemoryKind.ROUTINE,
            provenance = MemoryProvenance.OBSERVED,
            statement = "Пользователь часто открывает youtube",
            evidenceCount = 4,
            confidence = 0.72f,
            firstSeenMillis = now - 1000,
            lastSeenMillis = now,
            sensitivity = Sensitivity.PRIVATE,
            packageName = "com.google.android.youtube",
        )
        val d = MemoryPolicy().decide(c)
        assertEquals(MemoryDisposition.TEMPORARY, d.disposition)
        val e = writer.persist(d)!!
        assertEquals(MemoryStatus.ACTIVE, e.status)
        assertNotNull(e.expiresAtMillis)
        assertTrue(e.expiresAtMillis!! > now)
        assertEquals(MemoryProvenance.OBSERVED, e.provenance)
        assertEquals(4, e.evidenceCount)
    }

    @Test
    fun userStatedPreferenceStoreNoExpiry() {
        val e = service.rememberUserStatement(
            "Я предпочитаю тёмную тему",
            kind = MemoryKind.PREFERENCE,
            confidence = 1f,
        )!!
        assertEquals(MemoryDisposition.STORE, e.disposition)
        assertNull(e.expiresAtMillis)
        assertEquals(MemoryProvenance.USER_STATED, e.provenance)
        assertEquals(1, recall.preferences().size)
    }

    @Test
    fun observedNotAutoPermanent() {
        val c = MemoryCandidate(
            kind = MemoryKind.PREFERENCE,
            provenance = MemoryProvenance.OBSERVED,
            statement = "кажется любит chrome",
            evidenceCount = 5,
            confidence = 0.8f,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = Sensitivity.PRIVATE,
        )
        val d = MemoryPolicy().decide(c)
        assertEquals(MemoryDisposition.TEMPORARY, d.disposition)
        assertFalse(d.disposition == MemoryDisposition.STORE)
    }

    @Test
    fun updateRefreshesEvidenceAndStatus() {
        val c1 = MemoryCandidate(
            id = "m1",
            kind = MemoryKind.ROUTINE,
            provenance = MemoryProvenance.OBSERVED,
            statement = "часто Telegram",
            evidenceCount = 3,
            confidence = 0.6f,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = Sensitivity.PRIVATE,
            packageName = "org.telegram.messenger",
            evidenceObservationIds = listOf("a", "b"),
        )
        writer.persist(MemoryPolicy().decide(c1))
        now += 5_000
        val c2 = c1.copy(
            evidenceCount = 6,
            confidence = 0.8f,
            lastSeenMillis = now,
            evidenceObservationIds = listOf("c", "d"),
        )
        val updated = writer.persist(MemoryPolicy().decide(c2))!!
        assertEquals(MemoryStatus.UPDATED, updated.status)
        assertEquals(6, updated.evidenceCount)
        assertTrue(updated.evidenceObservationIds.containsAll(listOf("a", "b", "c", "d")))
    }

    @Test
    fun expireMarksExpired() {
        val c = MemoryCandidate(
            kind = MemoryKind.WORKING,
            provenance = MemoryProvenance.OBSERVED,
            statement = "short",
            evidenceCount = 3,
            confidence = 0.7f,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = Sensitivity.NORMAL,
        )
        val e = writer.persist(MemoryPolicy().decide(c))!!
        assertNotNull(e.expiresAtMillis)
        now = e.expiresAtMillis!! + 1
        assertEquals(1, repo.expire(now))
        assertEquals(MemoryStatus.EXPIRED, repo.get(e.id)!!.status)
    }

    @Test
    fun staleLifecycle() {
        val e = service.rememberUserStatement("ок", confidence = 0.92f)!!
        // Force lastObserved far past stale window for WORKING-like — PREFERENCE USER_STATED uses 180d
        val aged = e.copy(
            type = MemoryKind.EPISODIC,
            provenance = MemoryProvenance.OBSERVED,
            lastObservedAtMillis = now - MemoryRetention.staleAfterMillis(
                MemoryKind.EPISODIC,
                MemoryProvenance.OBSERVED,
            ) - 1,
            expiresAtMillis = null,
            status = MemoryStatus.ACTIVE,
        )
        repo.update(aged)
        now += 1
        assertTrue(repo.expire(now) >= 1)
        assertEquals(MemoryStatus.STALE, repo.get(e.id)!!.status)
    }

    @Test
    fun deleteSoft() {
        val e = service.rememberUserStatement("удали меня")!!
        assertTrue(repo.delete(e.id))
        assertEquals(MemoryStatus.DELETED, repo.get(e.id)!!.status)
        assertTrue(recall.active().none { it.id == e.id })
    }

    @Test
    fun recallByTypeAndText() {
        service.rememberUserStatement("Люблю джаз", kind = MemoryKind.PREFERENCE)
        service.rememberUserStatement("Люблю рок", kind = MemoryKind.PREFERENCE)
        assertEquals(2, recall.byType(MemoryKind.PREFERENCE).size)
        assertEquals(1, recall.textSearch("джаз").size)
        assertNotNull(recall.explain(recall.preferences().first().id))
    }

    @Test
    fun ingestFromSnapshotCreatesTemporary() {
        val pkg = "com.android.chrome"
        val recent = (1..4).map { i ->
            PhoneObservation(
                id = "o$i",
                timestampMillis = now - (4 - i) * 1000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = pkg,
                confidence = 0.9f,
            )
        }
        val snap = ContextSnapshot(
            context = PhoneContext(
                currentApp = pkg,
                previousApp = "com.android.settings",
                currentAppSource = ObservationSource.ACCESSIBILITY,
            ),
            takenAtMillis = now,
        )
        val stored = service.ingest(snap, recent)
        assertTrue(stored.any { it.type == MemoryKind.ROUTINE || it.type == MemoryKind.EPISODIC })
        assertTrue(stored.all { it.disposition != MemoryDisposition.REJECT })
        assertTrue(stored.none { it.provenance == MemoryProvenance.INFERRED })
    }

    @Test
    fun provenanceUserStatedVsObservedSeparated() {
        val observed = MemoryCandidate(
            kind = MemoryKind.ROUTINE,
            provenance = MemoryProvenance.OBSERVED,
            statement = "часто YouTube вечером",
            evidenceCount = 4,
            confidence = 0.8f,
            firstSeenMillis = now,
            lastSeenMillis = now,
            sensitivity = Sensitivity.PRIVATE,
        )
        val stated = observed.copy(
            provenance = MemoryProvenance.USER_STATED,
            confidence = 0.95f,
            kind = MemoryKind.PREFERENCE,
        )
        assertEquals(MemoryDisposition.TEMPORARY, MemoryPolicy().decide(observed).disposition)
        assertEquals(MemoryDisposition.STORE, MemoryPolicy().decide(stated).disposition)
    }

    @Test
    fun entityEncodeRoundtrip() {
        val raw = SqliteMemoryRepository.encodeEntities(mapOf("a" to "1", "b" to "x"))
        val back = SqliteMemoryRepository.decodeEntities(raw)
        assertEquals("1", back["a"])
        assertEquals("x", back["b"])
    }
}
