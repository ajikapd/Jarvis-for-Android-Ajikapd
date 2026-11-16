package com.jarvis.brain.cloud

import com.jarvis.core.cloud.CloudErrorClassifier
import com.jarvis.core.cloud.ErrorClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GigaChatAuthTest {
    private var now = 1_000_000_000_000L
    private var calls = 0
    private val sent = mutableListOf<Map<String, String>>()

    private fun answer(code: Int?, body: String?) {
        GigaChatAuth.forget()
        GigaChatAuth.clock = { now }
        GigaChatAuth.poster = GigaChatAuth.Poster { _, headers, _ -> calls++; sent += headers; code to body }
    }

    @After
    fun reset() = GigaChatAuth.forget()

    @Test
    fun `token is cached until a minute before it expires`() {
        answer(200, """{"access_token":"tok-1","expires_at":${(now + 30 * 60_000) / 1000}}""")
        assertEquals("tok-1", GigaChatAuth.token("KEY").token)
        assertEquals("tok-1", GigaChatAuth.token("KEY").token)
        assertEquals(1, calls)
        assertEquals("Basic KEY", sent[0]["Authorization"])
        now += 29 * 60_000 + 1
        GigaChatAuth.token("KEY")
        assertEquals(2, calls)
    }

    @Test
    fun `another key asks for a new token`() {
        answer(200, """{"access_token":"tok","expires_at":${now + 30 * 60_000}}""")
        GigaChatAuth.token("A")
        GigaChatAuth.token("B")
        assertEquals(2, calls)
    }

    @Test
    fun `errors keep their code so the chain classifies them`() {
        answer(401, """{"code":6,"message":"credentials doesn't match db data"}""")
        val r = GigaChatAuth.token("BAD")
        assertNull(r.token)
        assertEquals(ErrorClass.AUTH_OR_REGION, CloudErrorClassifier.classify(r.code, r.error))
        answer(null, "UnknownHostException: no network")
        assertEquals(ErrorClass.NETWORK_ERROR, CloudErrorClassifier.classify(GigaChatAuth.token("KEY").code, null))
    }

    @Test
    fun `expires_at in seconds or milliseconds`() {
        assertEquals(1_706_026_848_841L, GigaChatAuth.expiresAtMs(1_706_026_848_841L, 0))
        assertEquals(1_679_471_442_000L, GigaChatAuth.expiresAtMs(1_679_471_442L, 0))
    }

    @Test
    fun `quota exhausted waits instead of failing over forever`() {
        assertEquals(ErrorClass.RATE_LIMITED_DAILY, CloudErrorClassifier.classify(402, "Payment Required"))
    }
}
