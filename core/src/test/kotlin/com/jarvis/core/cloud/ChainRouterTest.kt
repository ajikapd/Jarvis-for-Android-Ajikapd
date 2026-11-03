package com.jarvis.core.cloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** Фейковый CloudCall: сценарии по ключу entry.modelId + учёт параллелизма и отмен. */
private class FakeCall(
    val script: Map<String, List<(CallListener) -> Unit>>
) : CloudCall {
    val starts = Collections.synchronizedList(mutableListOf<Pair<ChainEntry, Int>>())
    val cancels = Collections.synchronizedList(mutableListOf<Pair<ChainEntry, Int>>())
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    fun maxConcurrent(): Int = maxInFlight.get()

    override fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
        starts.add(entry to paramCandidateIndex)
        val current = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { old -> maxOf(old, current) }
        val steps = script[entry.modelId] ?: emptyList()
        val step = steps.getOrNull(paramCandidateIndex) ?: { }
        GlobalScope.launch(Dispatchers.Default) {
            try {
                step(listener)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return object : CancelHandle {
            override fun cancel() {
                inFlight.decrementAndGet()
                cancels.add(entry to paramCandidateIndex)
            }
        }
    }
}

private class RecordingSink : TokenSink {
    val first = mutableListOf<String>()
    val tokens = mutableListOf<String>()
    var completedUsage: Map<String, Any>? = null
    override fun onFirstToken(text: String) { first.add(text) }
    override fun onToken(text: String) { tokens.add(text) }
    override fun onComplete(usage: Map<String, Any>?) { completedUsage = usage }
}

private fun entry(id: String, params: Int = 3) = ChainEntry(
    providerId = "p-$id", modelId = "m-$id", label = id,
    baseUrl = "https://x/$id",
    paramCandidates = (0 until params).map { if (it == params - 1) emptyMap() else mapOf("k$it" to it) }
)

private val fastCfg = ChainConfig(hedgeAfterMs = 80, totalDeadlineMs = 3000, maxParallel = 2)

class ChainRouterTest {
    private fun health() = ChainHealth(clock = { System.currentTimeMillis() }, config = fastCfg)
    private val req = ChainRouter.Request()

    @Test
    fun hedge_triggersAndFasterWins() = runBlocking {
        val e1 = entry("slow")
        val e2 = entry("fast")
        val call = FakeCall(mapOf(
            "m-slow" to listOf({ l -> runBlocking { delay(500); l.onFirstToken("slow") } }),
            "m-fast" to listOf({ l -> runBlocking { delay(5); l.onFirstToken("fast-tok"); l.onToken("more"); l.onComplete(null) } })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1, e2), health(), call, sink)
        assertEquals("m-fast", r.entry.modelId)
        assertTrue(r.hedged)
        assertEquals("fast-tok", sink.first.single())
    }

    @Test
    fun loserCancelledAndTokensNotInSink() = runBlocking {
        val e1 = entry("slow")
        val e2 = entry("fast")
        val call = FakeCall(mapOf(
            "m-slow" to listOf({ l -> runBlocking { delay(400); l.onFirstToken("loser"); l.onComplete(null) } }),
            "m-fast" to listOf({ l -> runBlocking { delay(5); l.onFirstToken("winner"); l.onComplete(null) } })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1, e2), health(), call, sink)
        assertEquals("m-fast", r.entry.modelId)
        assertFalse(sink.tokens.any { it.contains("loser") })
        assertFalse(sink.first.any { it.contains("loser") })
    }

    @Test
    fun firstFails_nextStartsImmediately() = runBlocking {
        val e1 = entry("bad")
        val e2 = entry("good")
        val call = FakeCall(mapOf(
            "m-bad" to listOf({ l -> l.onError(503, "overloaded") }),
            "m-good" to listOf({ l -> l.onFirstToken("ok"); l.onComplete(null) })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1, e2), health(), call, sink)
        assertEquals("m-good", r.entry.modelId)
        assertEquals(1, r.failures.count { it == ErrorClass.OVERLOADED })
    }

    @Test
    fun emptyResponse_movesToNextEntry() = runBlocking {
        val e1 = entry("empty")
        val e2 = entry("full")
        val call = FakeCall(mapOf(
            "m-empty" to listOf({ l -> l.onComplete(null) }),
            "m-full" to listOf({ l -> l.onFirstToken("data"); l.onComplete(null) })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1, e2), health(), call, sink)
        assertEquals("m-full", r.entry.modelId)
        assertEquals(1, r.failures.count { it == ErrorClass.EMPTY_RESPONSE })
    }

    @Test
    fun http400_movesToNextParamCandidate() = runBlocking {
        val e1 = entry("retry", params = 3)
        val call = FakeCall(mapOf(
            "m-retry" to listOf(
                { l -> l.onError(400, "bad param") },
                { l -> l.onFirstToken("second-candidate-works"); l.onComplete(null) }
            )
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1), health(), call, sink)
        assertEquals("m-retry", r.entry.modelId)
        assertEquals(0, r.failures.size)
        assertTrue(call.starts.any { it.second == 1 })
    }

    @Test
    fun totalDeadline_allFailed() = runBlocking {
        val e1 = entry("stuck")
        val call = FakeCall(mapOf(
            "m-stuck" to listOf({ l -> runBlocking { delay(10_000); l.onFirstToken("late") } })
        ))
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 300, maxParallel = 2)
        try {
            ChainRouter(config = cfg).run(req, listOf(e1), health(), call, RecordingSink())
            fail("expected AllFailedException")
        } catch (e: AllFailedException) {
            // дедлайн: failures пуст (записи не падали, просто молчали), hedged false
            assertEquals(0, e.failures.size)
            assertFalse(e.hedged)
        }
    }

    @Test
    fun allFailedExhausted_carriesFailuresAndHedged() = runBlocking {
        val e1 = entry("f1")
        val e2 = entry("f2")
        val call = FakeCall(mapOf(
            "m-f1" to listOf({ l -> l.onError(503, "overloaded") }),
            "m-f2" to listOf({ l -> l.onComplete(null) }) // пустой ответ
        ))
        val cfg = ChainConfig(hedgeAfterMs = 30, totalDeadlineMs = 5000, maxParallel = 2)
        try {
            ChainRouter(config = cfg).run(req, listOf(e1, e2), health(), call, RecordingSink())
            fail("expected AllFailedException")
        } catch (e: AllFailedException) {
            // обе записи дали сбой: overloaded + empty_response
            assertEquals(listOf(ErrorClass.OVERLOADED, ErrorClass.EMPTY_RESPONSE), e.failures)
        }
    }

    @Test
    fun loserCancelledOnce_winnerNotCancelledBeforeComplete() = runBlocking {
        val e1 = entry("slow")
        val e2 = entry("fast")
        val call = FakeCall(mapOf(
            "m-slow" to listOf({ l -> runBlocking { delay(400); l.onFirstToken("loser"); l.onComplete(null) } }),
            "m-fast" to listOf({ l -> runBlocking { delay(5); l.onFirstToken("winner"); l.onComplete(null) } })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1, e2), health(), call, sink)
        assertEquals("m-fast", r.entry.modelId)
        // проигравший отменён ровно один раз
        val loserCancels = call.cancels.count { it.first.modelId == "m-slow" }
        assertEquals(1, loserCancels)
        // победитель не отменён (cancel не вызван ни разу для m-fast)
        assertEquals(0, call.cancels.count { it.first.modelId == "m-fast" })
    }

    @Test
    fun deadline_cancelsAllActive() = runBlocking {
        val e1 = entry("stuck1")
        val e2 = entry("stuck2")
        val call = FakeCall(mapOf(
            "m-stuck1" to listOf({ l -> runBlocking { delay(10_000); l.onFirstToken("late1") } }),
            "m-stuck2" to listOf({ l -> runBlocking { delay(10_000); l.onFirstToken("late2") } })
        ))
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 400, maxParallel = 2)
        try {
            ChainRouter(config = cfg).run(req, listOf(e1, e2), health(), call, RecordingSink())
            fail("expected AllFailedException")
        } catch (e: AllFailedException) { /* ok */ }
        // после дедлайна cancel вызван у всех активных
        val stuckCancels = call.cancels.count { it.first.modelId.startsWith("m-stuck") }
        assertEquals(2, stuckCancels)
    }

    @Test
    fun allUnavailable_allFailedWithoutCalls() = runBlocking {
        val e1 = entry("off")
        val call = FakeCall(emptyMap())
        val h = ChainHealth(clock = { 0L }, config = fastCfg)
        h.recordFailure(e1, ErrorClass.AUTH_OR_REGION)
        try {
            ChainRouter(config = fastCfg).run(req, listOf(e1), h, call, RecordingSink())
            fail("expected AllFailedException")
        } catch (e: AllFailedException) { /* ok */ }
        assertEquals(0, call.starts.size)
    }

    @Test
    fun maxParallel_neverExceeded() = runBlocking {
        val entries = (0 until 4).map { entry("s$it") }
        // все медленные: ни один не даёт токен — дедлайн наступит раньше
        val call = FakeCall(entries.associate { e ->
            e.modelId to listOf<(CallListener) -> Unit>({ l -> runBlocking { delay(10_000); l.onFirstToken("t") } })
        })
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 500, maxParallel = 2)
        try {
            ChainRouter(config = cfg).run(req, entries, health(), call, RecordingSink())
            fail("expected AllFailedException")
        } catch (e: AllFailedException) { /* ok — дедлайн */ }
        // ровно 2 параллельных вызова максимум (не меньше и не больше)
        assertEquals(2, call.maxConcurrent())
    }

    @Test
    fun thoughtToken_notCountedAsFirst() = runBlocking {
        val e1 = entry("think")
        val call = FakeCall(mapOf(
            "m-think" to listOf({ l ->
                l.onFirstToken("<thought>first thought")
                l.onFirstToken("</thought>")
                l.onFirstToken("real answer")
                l.onComplete(null)
            })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1), health(), call, sink)
        assertEquals("m-think", r.entry.modelId)
        assertEquals("real answer", sink.first.single())
    }

    @Test
    fun authError_disablesInHealth() = runBlocking {
        val e1 = entry("auth")
        val e2 = entry("ok")
        val call = FakeCall(mapOf(
            "m-auth" to listOf({ l -> l.onError(401, "bad key") }),
            "m-ok" to listOf({ l -> l.onFirstToken("fine"); l.onComplete(null) })
        ))
        val h = health()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1, e2), h, call, RecordingSink())
        assertEquals("m-ok", r.entry.modelId)
        assertEquals(1, r.failures.count { it == ErrorClass.AUTH_OR_REGION })
    }

    @Test
    fun resultFields_filled() = runBlocking {
        val e1 = entry("meas")
        val call = FakeCall(mapOf(
            "m-meas" to listOf({ l -> runBlocking { delay(30); l.onFirstToken("x"); l.onToken("y"); l.onComplete(mapOf("in" to 1)) } })
        ))
        val sink = RecordingSink()
        val r = ChainRouter(config = fastCfg).run(req, listOf(e1), health(), call, sink)
        assertEquals("m-meas", r.entry.modelId)
        assertFalse(r.hedged)
        assertTrue(r.timeToFirstTokenMs >= 0)
        assertEquals(mapOf("in" to 1), sink.completedUsage)
        assertEquals(listOf("x"), sink.first)
        assertEquals(listOf("y"), sink.tokens)
    }

    @Test
    fun paramIndexCache_betweenRunsOfSameRouter() = runBlocking {
        val e1 = entry("cached", params = 3)
        var fired400 = false
        val call1 = FakeCall(mapOf(
            "m-cached" to listOf(
                { l -> if (!fired400) { fired400 = true; l.onError(400, "bad") } else { l.onFirstToken("ok"); l.onComplete(null) } },
                { l -> l.onFirstToken("idx1"); l.onComplete(null) }
            )
        ))
        val router = ChainRouter(config = fastCfg)
        val r1 = router.run(req, listOf(e1), health(), call1, RecordingSink())
        assertEquals("m-cached", r1.entry.modelId)
        // второй run того же роутера: кэш должен указывать на индекс 1
        val call2 = FakeCall(mapOf(
            "m-cached" to listOf(
                { l -> l.onError(500, "idx0 must not run") }, // индекс 0 не должен запуститься
                { l -> l.onFirstToken("cached-ok"); l.onComplete(null) }
            )
        ))
        val r2 = router.run(req, listOf(e1), health(), call2, RecordingSink())
        assertEquals("m-cached", r2.entry.modelId)
        assertFalse(call2.starts.any { it.second == 0 })
        assertTrue(call2.starts.any { it.second == 1 })
    }
}
