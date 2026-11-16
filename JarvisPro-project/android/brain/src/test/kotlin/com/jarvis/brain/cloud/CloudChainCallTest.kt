package com.jarvis.brain.cloud

import com.jarvis.brain.aiprovider.CloudLlmHttpTransport
import com.jarvis.brain.aiprovider.CloudLlmHttpResponse
import com.jarvis.brain.aiprovider.CloudLlmStreamTransport
import com.jarvis.core.cloud.CallListener
import com.jarvis.core.cloud.ChainEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CloudChainCallTest {
    private val entry = ChainEntry(
        providerId = "groq", modelId = "m-1", label = "t",
        baseUrl = "https://base.example/v1",
        paramCandidates = listOf(
            mapOf("reasoning_effort" to "low"),
            emptyMap(),
        ),
    )

    private class RecordingListener : CallListener {
        val first = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var usage: Map<String, Any>? = null
        var errCode: Int? = null
        var errBody: String? = null
        val latch = CountDownLatch(1)
        override fun onFirstToken(text: String) { first.add(text) }
        override fun onToken(text: String) { tokens.add(text) }
        override fun onComplete(usage: Map<String, Any>?) { this.usage = usage; latch.countDown() }
        override fun onError(httpStatus: Int?, body: String?) { errCode = httpStatus; errBody = body; latch.countDown() }
        fun await() { latch.await(5, TimeUnit.SECONDS) }
    }

    private fun transport(lines: List<String>, code: Int = 200, capture: MutableList<String>? = null, headersCap: MutableList<Map<String, String>>? = null): CloudLlmStreamTransport =
        CloudLlmStreamTransport { _, headers, body, _, _, onLine ->
            capture?.add(body)
            headersCap?.add(headers)
            lines.forEach { if (!onLine(it)) return@CloudLlmStreamTransport code }
            code
        }

    private fun call(key: String? = "SECRET-KEY-1234567890", t: CloudLlmStreamTransport): CloudChainCall {
        // фейк реализует оба интерфейса, чтобы streamChat использовал его же
        val dual = DualTransport(t)
        return CloudChainCall(
            systemPrompt = "sys", history = listOf("user: hi"), userText = "go", maxTokens = 64,
            keyFor = { if (it == "groq") key else null },
            transport = dual,
            executor = Executors.newSingleThreadExecutor(),
        )
    }

    /** Обёртка: один stream-фейк используется и как http-транспорт. */
    private class DualTransport(private val stream: CloudLlmStreamTransport) : CloudLlmHttpTransport, CloudLlmStreamTransport {
        override fun postJson(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int) =
            CloudLlmHttpResponse(500, "")
        override fun postJsonStream(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int, onLine: (String) -> Boolean): Int =
            stream.postJsonStream(url, headers, body, connectTimeoutMs, readTimeoutMs, onLine)
    }

    private val okLines = listOf(
        "data: {\"choices\":[{\"delta\":{\"content\":\"Привет\"}}]}",
        "data: {\"choices\":[{\"delta\":{\"content\":\", мир\"}}]}",
        "data: {\"choices\":[{\"delta\":{},\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}]}",
        "data: [DONE]",
    )

    @Test
    fun successStream_firstTokenThenTokensAndUsage() {
        val l = RecordingListener()
        call(t = transport(okLines)).start(entry, 0, l)
        l.await()
        assertEquals(listOf("Привет"), l.first)
        assertEquals(listOf(", мир"), l.tokens)
        assertEquals(5, l.usage!!["prompt_tokens"])
        assertNull(l.errCode)
    }

    @Test
    fun thoughtChunks_doNotBreakStream() {
        val lines = listOf(
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"думаю\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
            "data: [DONE]",
        )
        val l = RecordingListener()
        call(t = transport(lines)).start(entry, 0, l)
        l.await()
        assertEquals(listOf("ok"), l.first)
    }

    @Test
    fun rateLimit429PerDay_onErrorWithBody() {
        val lines = listOf("", "429: Requests PerDay limit exceeded")
        val l = RecordingListener()
        call(t = transport(lines, code = 429)).start(entry, 0, l)
        l.await()
        assertEquals(429, l.errCode)
        assertTrue(l.errBody!!.contains("PerDay"))
    }

    @Test
    fun badRequest400_onError() {
        val l = RecordingListener()
        call(t = transport(listOf("", "bad param"), code = 400)).start(entry, 0, l)
        l.await()
        assertEquals(400, l.errCode)
    }

    @Test
    fun cloudflare403_1010_onError() {
        val l = RecordingListener()
        call(t = transport(listOf("error code: 1010"), code = 403)).start(entry, 0, l)
        l.await()
        assertEquals(403, l.errCode)
        assertTrue(l.errBody!!.contains("1010"))
    }

    @Test
    fun networkException_onErrorNull() {
        val throwing: CloudLlmStreamTransport = CloudLlmStreamTransport { _, _, _, _, _, _ -> throw java.io.IOException("conn reset") }
        val l = RecordingListener()
        call(t = throwing).start(entry, 0, l)
        l.await()
        assertNull(l.errCode)
        assertTrue(l.errBody!!.contains("conn reset"))
    }

    @Test
    fun cancelMidStream_noFurtherCallbacks() {
        // транспорт выдаёт строки с паузой после первой: cancel успевает до хвоста
        val firstTokenLatch = CountDownLatch(1)
        val l = object : CallListener {
            val first = mutableListOf<String>()
            val tokens = mutableListOf<String>()
            var usage: Map<String, Any>? = null
            var errCode: Int? = null
            override fun onFirstToken(text: String) { first.add(text); firstTokenLatch.countDown() }
            override fun onToken(text: String) { tokens.add(text) }
            override fun onComplete(usage: Map<String, Any>?) { this.usage = usage }
            override fun onError(httpStatus: Int?, body: String?) { errCode = httpStatus }
        }
        val streamT: CloudLlmStreamTransport = CloudLlmStreamTransport { _, _, _, _, _, onLine ->
            for (i in okLines.indices) {
                if (!onLine(okLines[i])) return@CloudLlmStreamTransport 200
                if (i == 0) Thread.sleep(250) // окно для cancel
            }
            200
        }
        val c = call(t = streamT)
        val handle = c.start(entry, 0, l)
        // первый токен пришёл
        assertTrue(firstTokenLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, l.first.size)
        // отмена посреди потока
        handle.cancel()
        val before = l.first.size + l.tokens.size
        Thread.sleep(500) // хвост потока должен быть подавлен
        val after = l.first.size + l.tokens.size
        assertEquals("callbacks arrived after cancel", before, after)
        assertNull("onComplete after cancel", l.usage)
        assertNull("onError after cancel", l.errCode)
    }

    @Test
    fun noKey_401() {
        val l = RecordingListener()
        call(key = null, t = transport(okLines)).start(entry, 0, l)
        l.await()
        assertEquals(401, l.errCode)
        assertEquals("api key missing", l.errBody)
    }

    @Test
    fun cancelBeforeFirstToken_noCallbacks() {
        val lateExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        // Поток занят до отмены, иначе под нагрузкой токен успевает прийти раньше cancel().
        val gate = java.util.concurrent.CountDownLatch(1)
        lateExecutor.execute { gate.await() }
        val l = RecordingListener()
        val c = CloudChainCall(
            systemPrompt = "sys", history = emptyList(), userText = "go", maxTokens = 16,
            keyFor = { "k" },
            transport = DualTransport(transport(okLines)),
            executor = lateExecutor,
        )
        val h = c.start(entry, 0, l)
        h.cancel()
        gate.countDown()
        l.latch.await(300, TimeUnit.MILLISECONDS)
        assertTrue(l.first.isEmpty())
        assertNull(l.errCode)
        lateExecutor.shutdownNow()
    }

    @Test
    fun keyNotInErrorBody() {
        val lines = listOf("", "error: key SECRET-KEY-1234567890 invalid")
        val l = RecordingListener()
        call(t = transport(lines, code = 401)).start(entry, 0, l)
        l.await()
        assertFalse(l.errBody!!.contains("SECRET-KEY"))
        assertTrue(l.errBody!!.contains("[REDACTED]"))
    }

    @Test
    fun paramCandidate_passedByIndex() {
        // индекс 1 — пустой map: reasoning_effort в теле нет
        val cap1 = mutableListOf<String>()
        val l1 = RecordingListener()
        call(t = transport(okLines, capture = cap1)).start(entry, 1, l1)
        l1.await()
        assertFalse(cap1.first().contains("reasoning_effort"))
        // индекс 0 — кандидат в теле
        val cap0 = mutableListOf<String>()
        val l0 = RecordingListener()
        call(t = transport(okLines, capture = cap0)).start(entry, 0, l0)
        l0.await()
        assertTrue(cap0.first().contains("\"reasoning_effort\":\"low\""))
    }

    @Test
    fun baseUrlAndAuthorization_sent() {
        val headersCap = mutableListOf<Map<String, String>>()
        val urlsCap = mutableListOf<String>()
        val t: CloudLlmStreamTransport = CloudLlmStreamTransport { url, headers, _, _, _, onLine ->
            urlsCap.add(url); headersCap.add(headers)
            okLines.forEach { if (!onLine(it)) return@CloudLlmStreamTransport 200 }
            200
        }
        val l = RecordingListener()
        call(t = t).start(entry, 0, l)
        l.await()
        assertTrue("url=$urlsCap", urlsCap.first() == "https://base.example/v1/chat/completions")
        assertEquals("Bearer SECRET-KEY-1234567890", headersCap.first()["Authorization"])
    }

    // 9б-2c A2: таймаут подключения ограничен ChainConfig.connectTimeoutMs (по умолчанию 4 с)

    @Test
    fun connectTimeoutMs_default4s_passedToTransport() {
        val caps = mutableListOf<Int>() // (connectTimeout, readTimeout)
        val t: CloudLlmStreamTransport = CloudLlmStreamTransport { _, _, _, connectTimeoutMs, readTimeoutMs, onLine ->
            caps.add(connectTimeoutMs * 1000 + readTimeoutMs)
            okLines.forEach { if (!onLine(it)) return@CloudLlmStreamTransport 200 }
            200
        }
        val l = RecordingListener()
        call(t = t).start(entry, 0, l)
        l.await()
        // connectTimeout = 4000 (ChainConfig.Default.connectTimeoutMs), readTimeout = 15000 (totalDeadline)
        assertEquals(4000 * 1000 + 15000, caps.first())
    }

    @Test
    fun hangingConnect_failsAfterConnectTimeout_not12s() {
        // транспорт «висит» на подключении: спит дольше connectTimeout, но меньше 12 с
        val observedConnectTimeout = java.util.concurrent.atomic.AtomicInteger(0)
        val hanging: CloudLlmStreamTransport = CloudLlmStreamTransport { _, _, _, connectTimeoutMs, _, _ ->
            observedConnectTimeout.set(connectTimeoutMs)
            // эмулируем поведение HttpURLConnection.connectTimeout: спим connectTimeout+запас → SocketTimeoutException
            Thread.sleep(connectTimeoutMs + 100L)
            throw java.net.SocketTimeoutException("connect timed out")
        }
        val l = RecordingListener()
        val t0 = System.currentTimeMillis()
        call(t = hanging).start(entry, 0, l)
        l.await()
        val elapsed = System.currentTimeMillis() - t0
        // реальный connectTimeout в этом тесте — дефолтные 4 с: ошибка приходит за ~4 с, а не за 12
        assertEquals(4000, observedConnectTimeout.get())
        assertTrue("elapsed=${elapsed}ms should be ~4s, not 12s", elapsed in 3900..8000)
        assertNull("connection error → errCode null", l.errCode)
    }

    /** Отмена одной попытки не должна задевать другую попытку того же экземпляра (хедж). */
    @Test
    fun cancelOneAttempt_doesNotKillOther() {
        val secondEntry = entry.copy(modelId = "second")
        val release = CountDownLatch(1)
        val t = CloudLlmStreamTransport { _, _, body, _, _, onLine ->
            if (body.contains("second")) {
                release.await(2, TimeUnit.SECONDS)
                okLines.forEach { if (!onLine(it)) return@CloudLlmStreamTransport 200 }
            }
            200
        }
        val c = CloudChainCall(
            systemPrompt = "sys", history = emptyList(), userText = "go", maxTokens = 64,
            keyFor = { "SECRET-KEY-1234567890" }, transport = DualTransport(t),
        )
        val loser = RecordingListener()
        val winner = RecordingListener()
        val loserHandle = c.start(entry, 0, loser)
        c.start(secondEntry, 0, winner)
        loserHandle.cancel()
        release.countDown()
        winner.await()
        assertEquals(listOf("Привет"), winner.first)
        assertEquals(listOf(", мир"), winner.tokens)
        assertEquals(5, winner.usage!!["prompt_tokens"])
    }

    /** Каждая попытка получает свой onFirstToken, даже когда идут параллельно. */
    @Test
    fun eachAttempt_getsOwnFirstToken() {
        val c = call(t = transport(okLines))
        val a = RecordingListener()
        val b = RecordingListener()
        c.start(entry, 0, a)
        a.await()
        c.start(entry, 0, b)
        b.await()
        assertEquals(listOf("Привет"), b.first)
    }

    /** Отмена обрывает соединение, заблокированное в чтении, а не ждёт следующей строки. */
    @Test
    fun cancel_abortsBlockedRead() {
        val aborted = CountDownLatch(1)
        val inRead = CountDownLatch(1)
        val t = object : CloudLlmHttpTransport, CloudLlmStreamTransport {
            override fun postJson(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int) =
                CloudLlmHttpResponse(500, "")
            override fun postJsonStream(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int, onLine: (String) -> Boolean): Int = 200
            override fun postJsonStreamAbortable(url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int, onAbortable: (() -> Unit) -> Unit, onLine: (String) -> Boolean): Int {
                onAbortable { aborted.countDown() }
                inRead.countDown()
                aborted.await(2, TimeUnit.SECONDS)
                throw java.io.IOException("socket closed")
            }
        }
        val l = RecordingListener()
        val c = CloudChainCall(
            systemPrompt = "sys", history = emptyList(), userText = "go", maxTokens = 64,
            keyFor = { "SECRET-KEY-1234567890" }, transport = t,
        )
        val h = c.start(entry, 0, l)
        assertTrue(inRead.await(2, TimeUnit.SECONDS))
        h.cancel()
        assertTrue("connection not aborted on cancel", aborted.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertNull("no error callback after cancel", l.errCode)
    }
}
