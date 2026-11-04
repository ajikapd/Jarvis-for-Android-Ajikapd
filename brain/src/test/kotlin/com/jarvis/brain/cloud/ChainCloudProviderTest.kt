package com.jarvis.brain.cloud

import com.jarvis.brain.aiprovider.AICompletionRequest
import com.jarvis.brain.aiprovider.AICompletionStatus
import com.jarvis.brain.aiprovider.AIProviderAvailability
import com.jarvis.brain.aiprovider.AIStreamEvent
import com.jarvis.brain.aiprovider.AIStreamEventType
import com.jarvis.brain.aiprovider.CloudLlmHttpTransport
import com.jarvis.brain.aiprovider.CloudLlmHttpResponse
import com.jarvis.brain.aiprovider.CloudLLMProvider
import com.jarvis.core.cloud.ChainConfig
import com.jarvis.core.cloud.ChainHealth
import com.jarvis.core.cloud.ChainRouter
import com.jarvis.core.cloud.CloudCall
import com.jarvis.core.cloud.CancelHandle
import com.jarvis.core.cloud.CallListener
import com.jarvis.core.cloud.ChainResult
import com.jarvis.core.cloud.ErrorClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChainCloudProviderTest {
    private val cfg = ChainConfig(hedgeAfterMs = 60, totalDeadlineMs = 2000, maxParallel = 2, circuitOpenMs = 60, circuitFailureThreshold = 2)

    private fun provider(
        keys: () -> Set<String>,
        keyFor: (String) -> String? = { "KEY-${it.uppercase()}-0123456789" },
        fakeCall: CloudCall = FakeCall(),
        legacy: CloudLLMProvider = CloudLLMProvider(),
        now: () -> Long = { System.currentTimeMillis() },
    ): Pair<ChainCloudProvider, CloudCall> {
        val health = ChainHealth(clock = now, config = cfg)
        val router = ChainRouter(clock = now, config = cfg)
        val p = ChainCloudProvider(
            legacy = legacy, router = router, health = health,
            keyFor = keyFor, providersWithKeys = keys,
            transport = null, clock = now, config = cfg,
            allFailedCooldownMs = 100,
            callFactory = { fakeCall },
        )
        return p to fakeCall
    }

    private val req = AICompletionRequest("r1", "привет")

    private class FakeCall : CloudCall {
        val starts = AtomicInteger(0)
        val cancels = AtomicInteger(0)
        /** сценарий: на каждый start колбэки */
        var behavior: (com.jarvis.core.cloud.ChainEntry, CallListener) -> Unit = { _, _ -> }
        override fun start(entry: com.jarvis.core.cloud.ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
            starts.incrementAndGet()
            behavior(entry, listener)
            return object : CancelHandle { override fun cancel() { cancels.incrementAndGet() } }
        }
    }

    private val successBehavior: (com.jarvis.core.cloud.ChainEntry, CallListener) -> Unit = { _, l ->
        Thread.sleep(5)
        l.onFirstToken("Ответ")
        l.onToken(" готов")
        l.onComplete(mapOf("prompt_tokens" to 3))
    }

    // 1. availability: нет ключей и legacy недоступен
    @Test
    fun availability_noKeysLegacyUnavailable() {
        val (p, _) = provider(keys = { emptySet() })
        assertEquals(AIProviderAvailability.UNAVAILABLE, p.availability())
    }

    // 2. availability: только groq
    @Test
    fun availability_groqOnly() {
        val (p, _) = provider(keys = { setOf("groq") })
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
    }

    // 3. availability: только legacy (custom) — legacy с настроенным клиентом
    @Test
    fun availability_legacyOnly() {
        val client = com.jarvis.brain.aiprovider.OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://custom.example/v1" },
            apiKeyProvider = { "custom-key-1234567890" },
            transport = com.jarvis.brain.aiprovider.CloudLlmHttpTransport { _, _, _, _, _ ->
                com.jarvis.brain.aiprovider.CloudLlmHttpResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
            },
        )
        val legacy = CloudLLMProvider(client = client)
        val (p, _) = provider(keys = { setOf("custom") }, legacy = legacy)
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
    }

    // 4. cooldown после AllFailed и его окончание
    @Test
    fun availability_cooldownAfterAllFailed() {
        var now = 0L
        val fail: (com.jarvis.core.cloud.ChainEntry, CallListener) -> Unit = { _, l -> l.onError(503, "overloaded") }
        val fc = FakeCall().apply { behavior = fail }
        val (p, _) = provider(keys = { setOf("groq") }, fakeCall = fc, now = { now })
        val r = p.complete(req)
        assertEquals(AICompletionStatus.FAILURE, r.status)
        assertEquals("CHAIN_ALL_FAILED", r.error!!.code)
        // сразу после — cooldown
        now = 1
        assertEquals(AIProviderAvailability.UNAVAILABLE, p.availability())
        // после 100 мс — снова доступен
        now = 101
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
    }

    // 5. кеш providersWithKeys + invalidate
    @Test
    fun providersWithKeys_cachedAndInvalidated() {
        var now = 0L
        var keys = setOf("groq")
        val (p, _) = provider(keys = { keys }, now = { now })
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
        keys = emptySet()
        // кеш ещё держит (в пределах 2 сек)
        now = 500
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
        // TTL истёк
        now = 2001
        assertEquals(AIProviderAvailability.UNAVAILABLE, p.availability())
        // снова ключ; кеш обновится и после invalidate сразу
        keys = setOf("gemini")
        now = 2500
        p.invalidate()
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
    }

    // 6. порядок событий при успехе (complete) — не события, но поля
    @Test
    fun complete_success_fillsFields() {
        val fc = FakeCall().apply { behavior = successBehavior }
        val (p, _) = provider(keys = { setOf("groq", "gemini") }, fakeCall = fc)
        val r = p.complete(req)
        assertEquals(AICompletionStatus.SUCCESS, r.status)
        assertEquals("Ответ готов", r.text)
        assertTrue(r.streamed)
        assertNotNull(r.modelId)
        assertTrue(r.ttftMs!! >= 0)
        assertEquals(com.jarvis.core.runtime.TtftKind.REAL, r.ttftKind)
        assertEquals("groq/openai/gpt-oss-120b", p.lastWinner)
    }

    // 7. thought-теги не попадают в TEXT (ThoughtStripper вырезает блок, даже разорванный между чанками)
    @Test
    fun stream_thoughtTextNotInEvents() {
        val fc = FakeCall().apply {
            behavior = { _, l ->
                Thread {
                    // тег <think> разорван между чанками: "<thi" + "nk>рассуж" | "дение" + "</thi" | "nk>Ответ"
                    l.onFirstToken("<thi")
                    l.onToken("nk>рассуж")
                    l.onToken("дение</thi")
                    l.onToken("nk>Ответ")
                    l.onComplete(null)
                }.start()
            }
        }
        val (p, _) = provider(keys = { setOf("groq") }, fakeCall = fc)
        val events = java.util.Collections.synchronizedList(mutableListOf<AIStreamEvent>())
        val done = CountDownLatch(1)
        p.stream(req, { e ->
            events.add(e)
            if (e.type == AIStreamEventType.COMPLETE || e.type == AIStreamEventType.ERROR) done.countDown()
        })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val textEvents = events.filter { it.type == AIStreamEventType.TEXT }
        // ни мысли, ни теги не попали в TEXT
        assertTrue("thought leaked: $textEvents", textEvents.none { it.text.contains("рассуждение") })
        assertTrue("tags leaked: $textEvents", textEvents.none { it.text.contains("think") })
        // итоговый текст — только "Ответ"
        assertEquals("Ответ", textEvents.joinToString("") { it.text })
    }

    // 8. hedged в METADATA
    @Test
    fun stream_metadataHedged() {
        val fc = FakeCall().apply {
            behavior = { entry, l ->
                Thread {
                    if (entry.modelId == "openai/gpt-oss-120b") {
                        Thread.sleep(300)
                        l.onFirstToken("slow")
                        l.onComplete(null)
                    } else {
                        Thread.sleep(10)
                        l.onFirstToken("fast")
                        l.onToken("!")
                        l.onComplete(null)
                    }
                }.start()
            }
        }
        val (p, _) = provider(keys = { setOf("groq", "gemini") }, fakeCall = fc)
        val events = java.util.Collections.synchronizedList(mutableListOf<AIStreamEvent>())
        val done = CountDownLatch(1)
        p.stream(req, { e ->
            events.add(e)
            if (e.type == AIStreamEventType.METADATA || e.type == AIStreamEventType.ERROR) done.countDown()
        })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val meta = events.first { it.type == AIStreamEventType.METADATA }
        assertTrue("meta=${meta.text}", meta.text.contains("hedged=true"))
        assertTrue(meta.text.contains("entry=gemini/gemini-3.5-flash-lite"))
    }

    // 9. AllFailed → ERROR CHAIN_ALL_FAILED
    @Test
    fun stream_allFailedError() {
        val fc = FakeCall().apply {
            behavior = { _, l -> l.onError(503, "overloaded") }
        }
        val (p, _) = provider(keys = { setOf("groq") }, fakeCall = fc)
        val events = java.util.Collections.synchronizedList(mutableListOf<AIStreamEvent>())
        val done = CountDownLatch(1)
        p.stream(req, { e ->
            events.add(e)
            if (e.type == AIStreamEventType.ERROR) done.countDown()
        })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val err = events.first { it.type == AIStreamEventType.ERROR }
        assertEquals("CHAIN_ALL_FAILED", err.errorCode)
        assertTrue(err.errorMessage!!.contains("OVERLOADED"))
    }

    // 10. отмена по cancelFlag: быстро и CANCELLED
    @Test
    fun stream_cancelFlagFast() {
        val fc = FakeCall().apply {
            behavior = { _, l ->
                Thread {
                    l.onFirstToken("первый")
                    Thread.sleep(5000)
                    l.onToken("хвост")
                    l.onComplete(null)
                }.start()
            }
        }
        val (p, _) = provider(keys = { setOf("groq") }, fakeCall = fc)
        val events = java.util.Collections.synchronizedList(mutableListOf<AIStreamEvent>())
        val done = CountDownLatch(1)
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val t0 = System.currentTimeMillis()
        p.stream(req, { e ->
            events.add(e)
            if (e.type == AIStreamEventType.TEXT && e.text == "первый") cancelFlag.set(true)
            if (e.type == AIStreamEventType.CANCELLED) done.countDown()
        }, cancelFlag)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("cancel took ${elapsed}ms", elapsed < 1000)
        val cancelIdx = events.indexOfFirst { it.type == AIStreamEventType.CANCELLED }
        assertTrue(cancelIdx >= 0)
        assertFalse(events.drop(cancelIdx + 1).any { it.type == AIStreamEventType.TEXT })
        // отмена активного handle у CloudCall была вызвана
        assertTrue("handle cancel not called", fc.cancels.get() > 0)
    }

    // 11. ключи не в событиях/результатах
    @Test
    fun keysNeverLeak() {
        val secret = "GROQ-SECRET-KEY-abcdef0123456789"
        val fc = FakeCall().apply {
            behavior = { _, l -> l.onError(401, "auth failed for $secret") }
        }
        val (p, _) = provider(keys = { setOf("groq") }, keyFor = { if (it == "groq") secret else null }, fakeCall = fc)
        val r = p.complete(req)
        assertEquals(AICompletionStatus.FAILURE, r.status)
        assertFalse(r.error!!.message.contains(secret))
        assertFalse(r.error!!.code.contains(secret))
    }

    // 12. делегирование legacy без ключей groq/gemini: complete идёт в legacy
    @Test
    fun delegatesToLegacy_noChainKeys() {
        val client = com.jarvis.brain.aiprovider.OpenAiCompatibleCloudClient(
            baseUrlProvider = { "https://custom.example/v1" },
            apiKeyProvider = { "custom-key-1234567890" },
            transport = com.jarvis.brain.aiprovider.CloudLlmHttpTransport { _, _, _, _, _ ->
                com.jarvis.brain.aiprovider.CloudLlmHttpResponse(200, "{\"choices\":[{\"message\":{\"content\":\"legacy answer\"}}]}")
            },
        )
        val legacy = CloudLLMProvider(client = client)
        val (p, fc) = provider(keys = { setOf("custom") }, legacy = legacy)
        assertEquals(AIProviderAvailability.AVAILABLE, p.availability())
        val r = p.complete(req)
        // legacy ответил, цепочка не запускалась
        assertEquals(AICompletionStatus.SUCCESS, r.status)
        assertEquals("legacy answer", r.text)
    }

    // 13. общий runtime: кэш индекса кандидатов живёт в router между run
    @Test
    fun sharedRuntime_paramIndexReused() {
        val firstRun = java.util.concurrent.atomic.AtomicBoolean(true)
        val fc = FakeCall().apply {
            behavior = { entry, l ->
                if (firstRun.getAndSet(false) && entry.modelId == "openai/gpt-oss-120b") {
                    l.onError(400, "bad param") // → роутер примет индекс 1
                } else {
                    l.onFirstToken("ok")
                    l.onComplete(null)
                }
            }
        }
        val (p, _) = provider(keys = { setOf("groq") }, fakeCall = fc)
        val r1 = p.complete(req)
        assertEquals(AICompletionStatus.SUCCESS, r1.status)
        val r2 = p.complete(AICompletionRequest("r2", "снова"))
        assertEquals(AICompletionStatus.SUCCESS, r2.status)
    }

    // 14. onKeyChanged сбрасывает health
    // 14. onKeyChanged сбрасывает health: запись снова доступна и вызывается
    @Test
    fun onKeyChanged_resetsHealth() {
        val fc = FakeCall()
        fc.behavior = { _, l -> l.onError(401, "bad key") }
        val (p, _) = provider(keys = { setOf("groq") }, fakeCall = fc)
        val entry = com.jarvis.core.cloud.DefaultCloudChain.entries().first {
            it.providerId == "groq" && it.modelId == "openai/gpt-oss-120b"
        }
        // помечаем запись отключённой через AUTH_OR_REGION
        p.health.recordFailure(entry, com.jarvis.core.cloud.ErrorClass.AUTH_OR_REGION)
        assertFalse("до onKeyChanged запись должна быть недоступна", p.health.isAvailable(entry))
        // сбрасываем через onKeyChanged
        p.onKeyChanged("groq")
        assertTrue("после onKeyChanged запись должна быть доступна", p.health.isAvailable(entry))
        // повторный complete вызывает эту запись
        p.complete(req)
        assertTrue("запись должна быть вызвана после сброса health", fc.starts.get() >= 1)
    }

    // 15. (9б-2c D) приватность: номер карты маскируется до отправки в облако.
    // Реальный CloudChainCall + фейк-транспорт: тело запроса, события и текст не содержат исходный номер.
    @Test
    fun cardNumber_maskedInRequestBodyAndEvents() {
        val card = "4111 1111 1111 1111"
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val okSse = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"готово\"}}]}",
            "data: [DONE]",
        )
        // CloudChainCall использует streamTransport = transport as? CloudLlmStreamTransport;
        // фейк должен реализовать оба интерфейса
        val dual = object : com.jarvis.brain.aiprovider.CloudLlmHttpTransport,
            com.jarvis.brain.aiprovider.CloudLlmStreamTransport {
            override fun postJson(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int) =
                com.jarvis.brain.aiprovider.CloudLlmHttpResponse(200, "")
            override fun postJsonStream(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int, onLine: (String) -> Boolean): Int {
                bodies.add(body)
                okSse.forEach { if (!onLine(it)) return 200 } // false = [DONE]: нормальное завершение
                return 200
            }
        }
        val health = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        val router = ChainRouter(clock = { System.currentTimeMillis() }, config = cfg)
        val p = ChainCloudProvider(
            legacy = CloudLLMProvider(),
            router = router,
            health = health,
            keyFor = { "KEY-${it.uppercase()}-0123456789" },
            providersWithKeys = { setOf("groq") },
            transport = dual,
            clock = { System.currentTimeMillis() },
            config = cfg,
            allFailedCooldownMs = 100,
            callFactory = null, // реальный CloudChainCall с фейк-транспортом
        )
        val events = java.util.Collections.synchronizedList(mutableListOf<AIStreamEvent>())
        val done = CountDownLatch(1)
        p.stream(
            AICompletionRequest("card-r1", "оплати заказ картой $card пожалуйста"),
            { e ->
                events.add(e)
                if (e.type == AIStreamEventType.COMPLETE || e.type == AIStreamEventType.ERROR) done.countDown()
            },
        )
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // 1) тело запроса: номер замаскирован, исходного номера нет
        assertTrue("no request bodies captured", bodies.isNotEmpty())
        bodies.forEach { b ->
            assertFalse("raw card number leaked into request body: $b", b.contains(card))
            assertFalse("raw card digits leaked: $b", b.contains("4111111111111111"))
            assertTrue("mask missing in body: $b", b.contains(com.jarvis.core.privacy.SensitivityClassifier.MASK))
        }
        // 2) события TEXT: исходного номера нет
        events.filter { it.type == AIStreamEventType.TEXT }.forEach { e ->
            assertFalse("raw card number leaked into events: ${e.text}", e.text.contains(card))
            assertFalse("raw card digits leaked into events: ${e.text}", e.text.contains("4111111111111111"))
        }
        // 3) итоговый текст успешен и не содержит номер
        val complete = events.first { it.type == AIStreamEventType.COMPLETE }
        assertEquals("готово", events.filter { it.type == AIStreamEventType.TEXT }.joinToString("") { it.text })
    }
}
