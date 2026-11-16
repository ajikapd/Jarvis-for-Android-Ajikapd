package com.jarvis.core.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudChainTests {
    @Test fun chainConfig_isDataClassWithDefaults() {
        val cfg = ChainConfig.Default
        assertEquals(2500L, cfg.hedgeAfterMs)
        assertEquals(15000L, cfg.totalDeadlineMs)
        assertEquals(2, cfg.maxParallel)
        assertEquals(6 * 60 * 60 * 1000L, cfg.dailyExhaustedCooldownMs)
        assertEquals(20, cfg.rateLimitedDefaultSeconds)
        assertEquals(60, cfg.rateLimitedMaxSeconds)
        assertEquals(5 * 60 * 1000L, cfg.circuitOpenMs)
        assertEquals(3, cfg.circuitFailureThreshold)
        val small = ChainConfig(hedgeAfterMs = 50, circuitOpenMs = 100, circuitFailureThreshold = 2)
        assertEquals(50L, small.hedgeAfterMs)
        assertEquals(100L, small.circuitOpenMs)
        assertEquals(2, small.circuitFailureThreshold)
    }

    @Test fun defaultChain_orderAndAvailability() {
        val entries = DefaultCloudChain.entries()
        assertEquals(6, entries.size)
        assertEquals("groq", entries[0].providerId)
        val avail = DefaultCloudChain.availableEntries(entries, setOf("gemini"))
        assertEquals(2, avail.size)
        assertTrue(avail.all { it.providerId == "gemini" })
    }

    @Test fun classify_table() {
        assertEquals(ErrorClass.BLOCKED_BY_CLOUDFLARE, CloudErrorClassifier.classify(403, "error 1010"))
        assertEquals(ErrorClass.AUTH_OR_REGION, CloudErrorClassifier.classify(401, "x"))
        assertEquals(ErrorClass.FORBIDDEN, CloudErrorClassifier.classify(403, "x"))
        assertEquals(ErrorClass.AUTH_OR_REGION, CloudErrorClassifier.classify(403, "API key not valid"))
        assertEquals(ErrorClass.AUTH_OR_REGION, CloudErrorClassifier.classify(400, "API key not valid"))
        assertEquals(ErrorClass.RATE_LIMITED_DAILY, CloudErrorClassifier.classify(429, "PerDay limit"))
        assertEquals(ErrorClass.RATE_LIMITED, CloudErrorClassifier.classify(429, "minute"))
        assertEquals(ErrorClass.OVERLOADED, CloudErrorClassifier.classify(503, ""))
        assertEquals(ErrorClass.SERVER_ERROR, CloudErrorClassifier.classify(500, ""))
        assertEquals(ErrorClass.BAD_REQUEST, CloudErrorClassifier.classify(400, "bad"))
        assertEquals(ErrorClass.NETWORK_ERROR, CloudErrorClassifier.classify(null, "x"))
    }

    @Test fun parseRetryAfter_table() {
        // Gemini
        assertEquals(20, CloudErrorClassifier.parseRetryAfterSeconds("""{"error":{"retryDelay":"20s"}}"""))
        // целые секунды не округляются вверх зря
        assertEquals(7, CloudErrorClassifier.parseRetryAfterSeconds("""{"retryDelay":"7s"}"""))
        // Groq: дробные секунды — вверх
        assertEquals(8, CloudErrorClassifier.parseRetryAfterSeconds("Please try again in 7.5s"))
        // Groq: минуты+секунды
        assertEquals(64, CloudErrorClassifier.parseRetryAfterSeconds("try again in 1m3.5s"))
        // Groq: только минуты
        assertEquals(120, CloudErrorClassifier.parseRetryAfterSeconds("try again in 2m"))
        // нет паттерна
        assertNull(CloudErrorClassifier.parseRetryAfterSeconds("no retry info"))
        assertNull(CloudErrorClassifier.parseRetryAfterSeconds(null))
        assertNull(CloudErrorClassifier.parseRetryAfterSeconds(""))
    }

    @Test fun health_rateLimited_usesParsedRetryAfter() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        // "try again in 7.5s" -> 8 сек (ceil)
        h.recordFailure(e, ErrorClass.RATE_LIMITED, retryAfterSeconds = CloudErrorClassifier.parseRetryAfterSeconds("try again in 7.5s"))
        now = 7_500
        assertFalse(h.isAvailable(e)) // 7.5с ещё мало (округлено до 8)
        now = 8_001
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_dailyExhausted() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.RATE_LIMITED_DAILY)
        assertFalse(h.isAvailable(e))
        now = 6 * 60 * 60 * 1000L + 1
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_rateLimited_cap() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.RATE_LIMITED, 100)
        assertFalse(h.isAvailable(e))
        // capped at 60s
        now = 60_000L - 1
        assertFalse(h.isAvailable(e))
        now = 60_000L + 1
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_rateLimited_default() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.RATE_LIMITED, null)
        now = 19_000L
        assertFalse(h.isAvailable(e))
        now = 20_001L
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_circuit() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        assertFalse(h.isAvailable(e))
        now = 5 * 60 * 1000L + 1
        assertTrue(h.isAvailable(e)) // half-open
    }

    @Test fun health_circuit_success_resets() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        repeat(2) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        h.recordSuccess(e)
        now = 0
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        assertFalse(h.isAvailable(e))
    }

    @Test fun health_auth_disabled_until_reset() {
        var now = 0L
        val h = ChainHealth(clock = { now })
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.AUTH_OR_REGION)
        assertFalse(h.isAvailable(e))
        h.reset(e.providerId)
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_snapshot() {
        val h = ChainHealth(clock = { 0L })
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.OVERLOADED)
        val snap = h.snapshot()
        assertEquals(1, snap.size)
    }

    @Test fun health_halfOpen_allowsOneProbe() {
        var now = 0L
        val cfg = ChainConfig(circuitOpenMs = 100, circuitFailureThreshold = 3)
        val h = ChainHealth(clock = { now }, config = cfg)
        val e = DefaultCloudChain.entries()[0]
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        now = 101
        assertTrue(h.isAvailable(e)) // полуоткрытие — проба допускается
        h.markProbeInFlight(e)
        assertFalse(h.isAvailable(e)) // второй параллельный пробный запрос не допускается
    }

    @Test fun health_halfOpen_successResets() {
        var now = 0L
        val cfg = ChainConfig(circuitOpenMs = 100, circuitFailureThreshold = 3)
        val h = ChainHealth(clock = { now }, config = cfg)
        val e = DefaultCloudChain.entries()[0]
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        now = 101
        h.markProbeInFlight(e)
        h.recordSuccess(e)
        // полностью сброшено — доступна сразу, не полуоткрытие
        now = 101
        assertTrue(h.isAvailable(e))
        now = 101
        assertTrue(h.isAvailable(e)) // вторая проба тоже допустима — сброс
    }

    @Test fun health_halfOpen_failureReopens() {
        var now = 0L
        val cfg = ChainConfig(circuitOpenMs = 100, circuitFailureThreshold = 3)
        val h = ChainHealth(clock = { now }, config = cfg)
        val e = DefaultCloudChain.entries()[0]
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        now = 101
        assertTrue(h.isAvailable(e)) // полуоткрытие
        h.markProbeInFlight(e)
        h.recordFailure(e, ErrorClass.OVERLOADED)
        assertFalse(h.isAvailable(e)) // снова открыто на 5 мин
        now = 201
        assertTrue(h.isAvailable(e)) // после нового circuitOpenMs снова полуоткрытие
    }

    @Test fun health_badRequest_paramsNotExhausted_isNotFailure() {
        val h = ChainHealth(clock = { 0L })
        val e = DefaultCloudChain.entries()[0]
        repeat(3) { h.recordFailure(e, ErrorClass.BAD_REQUEST, paramsExhausted = false) }
        assertTrue(h.isAvailable(e)) // кандидаты не исчерпаны — здоровья не задето
        assertEquals(0, h.snapshot()["groq/openai/gpt-oss-120b"]!!.consecutiveFailures)
    }

    @Test fun health_badRequest_paramsExhausted_isFailure() {
        var now = 0L
        val cfg = ChainConfig(circuitOpenMs = 100, circuitFailureThreshold = 2)
        val h = ChainHealth(clock = { now }, config = cfg)
        val e = DefaultCloudChain.entries()[0]
        repeat(2) { h.recordFailure(e, ErrorClass.BAD_REQUEST, paramsExhausted = true) }
        assertFalse(h.isAvailable(e)) // circuit открыт
    }

    @Test fun thoughtStripper_basic() {
        val ts = ThoughtStripper()
        assertEquals("hello", ts.strip("<think>x</think>hello"))
    }

    @Test fun thoughtStripper_splitTags() {
        val ts = ThoughtStripper()
        assertEquals("", ts.strip("<think>abc"))
        assertEquals("done", ts.strip("def</think>done"))
    }

    @Test fun thoughtStripper_unclosed() {
        val ts = ThoughtStripper()
        assertEquals("", ts.strip("<think>never closed"))
        assertEquals("", ts.flush())
    }

    @Test fun thoughtStripper_thoughtTag() {
        val ts = ThoughtStripper()
        assertEquals("result", ts.strip("<thought>reason</thought>result"))
    }
}
