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

    @Test
    fun halfOpen_NthreadsLatch_onlyOneProbe() = runBlocking {
        val e1 = entry("ho")
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 3000, maxParallel = 2, circuitOpenMs = 100, circuitFailureThreshold = 3)
        val h = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        // открыть circuit
        repeat(3) { h.recordFailure(e1, ErrorClass.OVERLOADED) }
        // ждать полуоткрытия (барьер до старта потоков)
        delay(cfg.circuitOpenMs + 50)
        val call = FakeCall(mapOf(
            "m-ho" to listOf({ l -> runBlocking { delay(400); l.onFirstToken("probe-ok"); l.onComplete(null) } })
        ))
        // N=8 потоков стартуют одновременно через CountDownLatch — без задержек
        val n = 8
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(n)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
        repeat(n) {
            pool.submit {
                try { startLatch.await() } catch (_: InterruptedException) {}
                try {
                    runBlocking { ChainRouter(config = cfg).run(req, listOf(e1), h, call, RecordingSink()) }
                    results.add(true)
                } catch (e: AllFailedException) {
                    results.add(false)
                } finally { doneLatch.countDown() }
            }
        }
        startLatch.countDown()
        doneLatch.await()
        pool.shutdown()
        // ровно один пробный start() на запись — tryAcquireProbe атомарен
        assertEquals(1, call.starts.size)
        // ровно один успех, остальные AllFailed
        assertEquals(1, results.count { it })
        assertEquals(n - 1, results.count { !it })
    }

    @Test
    fun halfOpenThird_unstartedProbe_releasedAfterRun() = runBlocking {
        // e3 — полуоткрытая третья запись; e1 отвечает успехом мгновенно,
        // e3 даже не стартует (maxParallel=2, победитель мгновенный)
        val e1 = entry("win")
        val e3 = entry("hopen")
        val cfg = ChainConfig(hedgeAfterMs = 1000, totalDeadlineMs = 3000, maxParallel = 2, circuitOpenMs = 50, circuitFailureThreshold = 2)
        val h = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        // открыть circuit у e3
        repeat(2) { h.recordFailure(e3, ErrorClass.OVERLOADED) }
        delay(cfg.circuitOpenMs + 30) // полуоткрытие
        // до run: запись полуоткрыта (isAvailable true, проба свободна)
        // (tryAcquireProbe не вызываем — иначе сами пометим пробу)
        val call = FakeCall(mapOf(
            "m-win" to listOf({ l -> l.onFirstToken("fast-win"); l.onComplete(null) }),
            "m-hopen" to listOf({ l -> runBlocking { delay(3000); l.onFirstToken("never") } })
        ))
        // победитель e1 — успех; e3 не стартовала (hedgeAfterMs больше времени run)
        val r = ChainRouter(config = cfg).run(req, listOf(e1, e3), h, call, RecordingSink())
        assertEquals("m-win", r.entry.modelId)
        // после run(): запись снова доступна для пробы (пометка снята releaseProbe)
        assertTrue(h.tryAcquireProbe(e3))
    }

    @Test
    fun halfOpenLoser_cancelledProbe_releasedAfterRun() = runBlocking {
        // e2 — полуоткрытая, стартует как дубль и проигрывает (отменена)
        val e1 = entry("w1")
        val e2 = entry("hlose")
        val cfg = ChainConfig(hedgeAfterMs = 30, totalDeadlineMs = 3000, maxParallel = 2, circuitOpenMs = 50, circuitFailureThreshold = 2)
        val h = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        // circuit открыт у e2
        repeat(2) { h.recordFailure(e2, ErrorClass.OVERLOADED) }
        delay(cfg.circuitOpenMs + 30) // полуоткрытие
        val failuresBefore = h.snapshot()["p-hlose/m-hlose"]!!.consecutiveFailures
        val call = FakeCall(mapOf(
            "m-w1" to listOf({ l -> runBlocking { delay(40); l.onFirstToken("win"); l.onComplete(null) } }),
            "m-hlose" to listOf({ l -> runBlocking { delay(3000); l.onFirstToken("late-loser") } })
        ))
        // e1 побеждает, e2 отменена как проигравший дубль
        val r = ChainRouter(config = cfg).run(req, listOf(e1, e2), h, call, RecordingSink())
        assertEquals("m-w1", r.entry.modelId)
        // после run(): проба e2 освобождена, запись снова доступна
        assertTrue("probe not released after loser cancel", h.tryAcquireProbe(e2))
        // счётчик сбоев не изменился (отмена — не сбой)
        assertEquals(failuresBefore, h.snapshot()["p-hlose/m-hlose"]!!.consecutiveFailures)
    }

    @Test
    fun unavailableEntry_skipped_noFailureRecorded() = runBlocking {
        val e1 = entry("skip1")  // станет недоступной (проба in-flight)
        val e2 = entry("ok2")
        val cfg = ChainConfig(hedgeAfterMs = 30, totalDeadlineMs = 3000, maxParallel = 2, circuitOpenMs = 50, circuitFailureThreshold = 2)
        val h = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        // открыть circuit у e1 и перевести в полуоткрытие
        repeat(2) { h.recordFailure(e1, ErrorClass.OVERLOADED) }
        delay(cfg.circuitOpenMs + 30)
        // занять пробу e1 из «вне» — как будто другой run держит её
        assertTrue(h.tryAcquireProbe(e1))
        val failuresBefore = h.snapshot()["p-skip1/m-skip1"]!!.consecutiveFailures
        val call = FakeCall(mapOf(
            "m-skip1" to listOf({ l -> runBlocking { delay(3000); l.onFirstToken("should-not-run") } }),
            "m-ok2" to listOf({ l -> l.onFirstToken("fine"); l.onComplete(null) })
        ))
        // e1 недоступна (проба занята) — роутер должен её пропустить и пойти к e2
        val r = ChainRouter(config = cfg).run(req, listOf(e1, e2), h, call, RecordingSink())
        assertEquals("m-ok2", r.entry.modelId)
        // e1 даже не стартовала и её нет в failures (пропуск — не сбой)
        assertEquals(0, call.starts.count { it.first.modelId == "m-skip1" })
        assertEquals(0, r.failures.size)
        // счётчик сбоев e1 не изменился
        assertEquals(failuresBefore, h.snapshot()["p-skip1/m-skip1"]!!.consecutiveFailures)
    }

    @Test
    fun halfOpen_probeSuccess_closesCircuit() = runBlocking {
        val e1 = entry("ho2")
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 3000, maxParallel = 2, circuitOpenMs = 60, circuitFailureThreshold = 2)
        val h = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        repeat(2) { h.recordFailure(e1, ErrorClass.OVERLOADED) }
        delay(cfg.circuitOpenMs + 30)
        // проба успешна
        val call = FakeCall(mapOf(
            "m-ho2" to listOf({ l -> l.onFirstToken("ok"); l.onComplete(null) })
        ))
        ChainRouter(config = cfg).run(req, listOf(e1), h, call, RecordingSink())
        // успех закрыл circuit: запись доступна сразу, без задержки
        assertTrue(h.isAvailable(e1))
    }

    @Test
    fun halfOpen_probeFailure_reopens() = runBlocking {
        val e1 = entry("ho3")
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 5000, maxParallel = 2, circuitOpenMs = 60, circuitFailureThreshold = 2)
        val h = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        repeat(2) { h.recordFailure(e1, ErrorClass.OVERLOADED) }
        delay(cfg.circuitOpenMs + 30)
        // проба падает
        val call = FakeCall(mapOf(
            "m-ho3" to listOf({ l -> l.onError(503, "still bad") })
        ))
        try {
            ChainRouter(config = cfg).run(req, listOf(e1), h, call, RecordingSink())
            fail("expected AllFailedException")
        } catch (e: AllFailedException) { /* ok */ }
        // circuit снова открыт
        assertFalse(h.isAvailable(e1))
        // и откроется заново только после circuitOpenMs
        delay(cfg.circuitOpenMs + 30)
        assertTrue(h.isAvailable(e1))
    }

    @Test
    fun cancelRace_handleAssignedAfterCancel() = runBlocking {
        val e1 = entry("race1")
        val e2 = entry("race2")
        val cancelLatch = java.util.concurrent.ConcurrentHashMap<String, Int>()
        // race2 выдаёт handle с задержкой; победитель (race1) появляется раньше
        val call = object : CloudCall {
            override fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
                if (entry.modelId == "m-race1") {
                    GlobalScope.launch { delay(3); listener.onFirstToken("win"); listener.onComplete(null) }
                    return object : CancelHandle { override fun cancel() { cancelLatch.merge("m-race1", 1, Int::plus) } }
                }
                // победитель приходит до присвоения handle: cancelAttempt ставит флаг,
                // затем assignHandle вызывает cancel()
                return object : CancelHandle {
                    override fun cancel() { cancelLatch.merge("m-race2", 1, Int::plus) }
                }.also {
                    GlobalScope.launch {
                        delay(30)
                        listener.onFirstToken("loser-late")
                    }
                }
            }
        }
        // мгновенный hedge: обе записи запускаются сразу, победитель — через 3мс
        val cfg = ChainConfig(hedgeAfterMs = 1, totalDeadlineMs = 2000, maxParallel = 2)
        val r = ChainRouter(config = cfg).run(req, listOf(e1, e2), health(), call, RecordingSink())
        assertEquals("m-race1", r.entry.modelId)
        // подождать, пока отложенный handle присвоится и cancel догонит
        delay(150)
        // cancel всё равно вызван у отложенного проигравшего
        assertEquals(1, cancelLatch["m-race2"])
    }

    @Test
    fun callerCancel_activeHandlesCancelled_noSinkAfter() = runBlocking {
        val e1 = entry("cc1")
        val e2 = entry("cc2")
        val cancelLatch = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val sinkEvents = java.util.concurrent.atomic.AtomicInteger(0)
        val call = object : CloudCall {
            override fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
                // медленные ответы — отмена придёт раньше
                GlobalScope.launch { delay(5000); listener.onFirstToken("late-${entry.modelId}") }
                return object : CancelHandle {
                    override fun cancel() { cancelLatch.merge(entry.modelId, 1, Int::plus) }
                }
            }
        }
        val sink = object : TokenSink {
            override fun onFirstToken(text: String) { sinkEvents.incrementAndGet() }
            override fun onToken(text: String) { sinkEvents.incrementAndGet() }
            override fun onComplete(usage: Map<String, Any>?) { }
        }
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 5000, maxParallel = 2)
        val job = GlobalScope.launch { ChainRouter(config = cfg).run(req, listOf(e1, e2), health(), call, sink) }
        delay(150) // оба активны
        job.cancelAndJoin()
        // все активные handles отменены ровно по одному разу
        assertEquals(1, cancelLatch["m-cc1"])
        assertEquals(1, cancelLatch["m-cc2"])
        // в sink ничего не пришло
        assertEquals(0, sinkEvents.get())
    }

    @Test
    fun winnerWithoutComplete_deadlineSafe_noNpe() = runBlocking {
        val e1 = entry("noc")
        // победитель даёт первый токен, но Complete не приходит — дедлайн
        val call = FakeCall(mapOf(
            "m-noc" to listOf({ l -> runBlocking { delay(10); l.onFirstToken("tok") } /* нет onComplete */ })
        ))
        val cfg = ChainConfig(hedgeAfterMs = 50, totalDeadlineMs = 300, maxParallel = 2)
        try {
            ChainRouter(config = cfg).run(req, listOf(e1), health(), call, RecordingSink())
            fail("expected AllFailedException on deadline")
        } catch (e: AllFailedException) {
            // дедлайн: победитель был, но не завершился — AllFailed без NPE
            assertNotNull(e)
        }
    }

    @Test
    fun stress_200runs_singleWinnerInvariant() = runBlocking {
        val cfg = ChainConfig(hedgeAfterMs = 40, totalDeadlineMs = 2000, maxParallel = 2)
        val entries = (0 until 3).map { entry("st$it") }
        repeat(200) { iter ->
            val rnd = java.util.Random(iter.toLong())
            // гарантия успеха: st0 всегда отвечает быстро (≤20мс), остальные — случайно
            val call = FakeCall(entries.associate { e ->
                val maxDelay = if (e.modelId == "m-st0") 20 else 200
                e.modelId to listOf<(CallListener) -> Unit>({ l ->
                    runBlocking {
                        delay(rnd.nextInt(maxDelay).toLong())
                        l.onFirstToken("tok-${e.modelId}")
                        delay(1)
                        l.onToken("cont-${e.modelId}")
                        l.onComplete(null)
                    }
                })
            })
            val firstCount = java.util.concurrent.atomic.AtomicInteger(0)
            val tokens = Collections.synchronizedList(mutableListOf<String>())
            val sink = object : TokenSink {
                override fun onFirstToken(text: String) { firstCount.incrementAndGet() }
                override fun onToken(text: String) { tokens.add(text) }
                override fun onComplete(usage: Map<String, Any>?) { }
            }
            // AllFailed не допускается: st0 отвечает ≤20мс при дедлайне 2000мс
            val r = ChainRouter(config = cfg).run(req, entries, health(), call, sink)
            // onFirstToken ровно 1 раз
            assertEquals("iter=$iter firstToken", 1, firstCount.get())
            // токены только от победителя: все содержат modelId победителя
            val winnerId = r.entry.modelId
            assertTrue("iter=$iter tokens not from winner: $tokens", tokens.all { it.contains(winnerId) })
        }
    }
}
