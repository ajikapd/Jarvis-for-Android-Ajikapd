package com.jarvis.brain.cloud

import com.jarvis.brain.aiprovider.AICompletionRequest
import com.jarvis.brain.aiprovider.AIProviderAvailability
import com.jarvis.brain.aiprovider.AIProviderRegistry
import com.jarvis.brain.aiprovider.AIRequestComplexity
import com.jarvis.brain.aiprovider.AIRouter
import com.jarvis.brain.aiprovider.AiTaskProfile
import com.jarvis.brain.aiprovider.ChatSentenceSink
import com.jarvis.brain.aiprovider.ChatStreamPipeline
import com.jarvis.brain.aiprovider.CloudLLMProvider
import com.jarvis.brain.aiprovider.CloudLlmHttpResponse
import com.jarvis.brain.aiprovider.CloudLlmHttpTransport
import com.jarvis.brain.aiprovider.CloudLlmStreamTransport
import com.jarvis.brain.aiprovider.OpenAiCompatibleCloudClient
import com.jarvis.core.cloud.ChainConfig
import com.jarvis.core.cloud.ChainHealth
import com.jarvis.core.cloud.ChainRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Сквозной путь голосового чата: ChatStreamPipeline → AIRouter → ChainCloudProvider →
 * ChainRouter → CloudChainCall. Фейк только HTTP-транспорт: подмена CloudCall скрыла бы
 * дефекты общего состояния попыток и контракта stream.
 */
class ChainStreamPipelineTest {
    private fun delta(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}"

    /** [script] получает тело запроса и onLine; результат — HTTP-код. */
    private class Transport(val script: (String, (String) -> Boolean) -> Int) : CloudLlmHttpTransport, CloudLlmStreamTransport {
        override fun postJson(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int) =
            CloudLlmHttpResponse(500, "")
        override fun postJsonStream(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int, onLine: (String) -> Boolean): Int =
            script(body, onLine)
    }

    private class Fixture(cfg: ChainConfig, transport: CloudLlmHttpTransport) {
        val health = ChainHealth(clock = { System.currentTimeMillis() }, config = cfg)
        val provider = ChainCloudProvider(
            legacy = CloudLLMProvider(client = OpenAiCompatibleCloudClient({ "https://example.com/v1" }, { "k" })),
            router = ChainRouter(config = cfg),
            health = health,
            keyFor = { "TEST-KEY-0123456789" },
            providersWithKeys = { setOf("groq", "gemini") },
            transport = transport,
            config = cfg,
        )
        val pipeline = ChatStreamPipeline(AIRouter(AIProviderRegistry().apply { register(provider) }))
    }

    private val profile = AiTaskProfile.forChat(
        isOnline = true, preferCloud = true, complexity = AIRequestComplexity.SIMPLE,
        requestId = "r1", userTextLen = 6,
    )
    private val req = AICompletionRequest("r1", "привет")
    private val cfg = ChainConfig(hedgeAfterMs = 60, totalDeadlineMs = 1_000, streamIdleTimeoutMs = 400)

    @Test
    fun returnsFullTextAndSentences_notEmptySuccess() {
        val f = Fixture(cfg, Transport { _, onLine ->
            Thread.sleep(20)
            onLine(delta("Привет, мир. "))
            onLine(delta("Как дела?"))
            onLine("data: [DONE]")
            200
        })
        val sentences = CopyOnWriteArrayList<String>()
        val r = f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { s, _ -> sentences.add(s); true })
        assertEquals("Привет, мир. Как дела?", r.fullText)
        assertFalse(r.failed)
        assertFalse(r.cancelled)
        assertEquals(r.fullText, sentences.joinToString(" "))
    }

    @Test
    fun hedgedWinner_finishesAnswer() {
        val f = Fixture(cfg, Transport { body, onLine ->
            if (body.contains("gpt-oss-120b")) Thread.sleep(600)
            for (t in listOf("Раз. ", "Два. ", "Три.")) {
                Thread.sleep(20)
                if (!onLine(delta(t))) return@Transport 200
            }
            onLine("data: [DONE]")
            200
        })
        val t0 = System.currentTimeMillis()
        val r = f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { _, _ -> true })
        assertEquals("Раз. Два. Три.", r.fullText)
        assertTrue(System.currentTimeMillis() - t0 < 500)
        assertTrue(f.provider.lastHedged)
    }

    @Test
    fun allEntriesFail_pipelineSeesFailure() {
        val f = Fixture(cfg, Transport { _, _ -> 503 })
        val r = f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { _, _ -> true })
        assertEquals("", r.fullText)
        assertTrue("result=$r", r.failed || r.fallbackUsed)
        assertEquals(AIProviderAvailability.UNAVAILABLE, f.provider.availability())
    }

    /** Перебивание пользователем: без ожидания дедлайна, ложных сбоев и cooldown. */
    @Test
    fun bargeIn_stopsFast_noFalseFailure() {
        val release = CountDownLatch(1)
        val f = Fixture(cfg, Transport { _, onLine ->
            onLine(delta("Начало ответа. "))
            release.await(3, TimeUnit.SECONDS)
            200
        })
        val t0 = System.currentTimeMillis()
        val r = f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { _, _ -> false })
        val ms = System.currentTimeMillis() - t0
        release.countDown()
        assertTrue(r.cancelled)
        assertTrue("took ${ms}ms", ms < 500)
        assertEquals(AIProviderAvailability.AVAILABLE, f.provider.availability())
        assertTrue(f.health.snapshot().toString(), f.health.snapshot().values.all { it.consecutiveFailures == 0 })
    }

    /** Обрыв посреди ответа: сказанное остаётся, перезапуска и cooldown нет. */
    @Test
    fun midStreamBreak_keepsSpokenPart() {
        val f = Fixture(cfg, Transport { _, onLine ->
            onLine(delta("Первая фраза. "))
            throw java.io.IOException("connection reset")
        })
        val sentences = CopyOnWriteArrayList<String>()
        val t0 = System.currentTimeMillis()
        val r = f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { s, _ -> sentences.add(s); true })
        assertTrue(System.currentTimeMillis() - t0 < 500)
        assertEquals(listOf("Первая фраза."), sentences.toList())
        assertEquals("partial_then_error_no_restart", r.fallbackReason)
        assertEquals(AIProviderAvailability.AVAILABLE, f.provider.availability())
    }

    /** Вызов инструмента не озвучивается, даже придя по кускам, и дойдёт до оркестратора целиком. */
    @Test
    fun toolAction_notSpoken() {
        val f = Fixture(cfg, Transport { _, onLine ->
            listOf("AC", "T {", "\\\"tool\\\":\\\"screenshot\\\"", "}").forEach { onLine(delta(it)) }
            onLine("data: [DONE]")
            200
        })
        val sentences = CopyOnWriteArrayList<String>()
        val r = f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { s, _ -> sentences.add(s); true })
        assertEquals("ACT {\"tool\":\"screenshot\"}", r.fullText)
        assertTrue(sentences.toString(), sentences.isEmpty())
    }

    /** Ответ, похожий на вызов только началом, озвучивается полностью, без потерь. */
    @Test
    fun textStartingLikeMarker_spokenWhole() {
        val f = Fixture(cfg, Transport { _, onLine ->
            listOf("A", "pple — это компания из Купертино.").forEach { onLine(delta(it)) }
            onLine("data: [DONE]")
            200
        })
        val sentences = CopyOnWriteArrayList<String>()
        f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { s, _ -> sentences.add(s); true })
        assertEquals("Apple — это компания из Купертино.", sentences.joinToString(" "))
    }

    @Test
    fun chatRequest_carriesToolCatalog() {
        val bodies = CopyOnWriteArrayList<String>()
        val f = Fixture(cfg, Transport { body, onLine ->
            bodies.add(body)
            onLine(delta("Ок."))
            onLine("data: [DONE]")
            200
        })
        f.pipeline.streamChat(profile, req, 1L, { false }, ChatSentenceSink { _, _ -> true })
        assertTrue(bodies.first().contains("flashlight"))
    }
}
