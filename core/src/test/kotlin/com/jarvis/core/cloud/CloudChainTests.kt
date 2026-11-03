package com.jarvis.core.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudChainTests {
    @Test fun defaultChain_orderAndAvailability() {
        val entries = DefaultCloudChain.entries()
        assertEquals(4, entries.size)
        assertEquals("groq", entries[0].providerId)
        val avail = DefaultCloudChain.availableEntries(entries, setOf("gemini"))
        assertEquals(2, avail.size)
        assertTrue(avail.all { it.providerId == "gemini" })
    }

    @Test fun classify_table() {
        assertEquals(ErrorClass.BLOCKED_BY_CLOUDFLARE, CloudErrorClassifier.classify(403, "error 1010"))
        assertEquals(ErrorClass.AUTH_OR_REGION, CloudErrorClassifier.classify(401, "x"))
        assertEquals(ErrorClass.AUTH_OR_REGION, CloudErrorClassifier.classify(400, "API key not valid"))
        assertEquals(ErrorClass.RATE_LIMITED_DAILY, CloudErrorClassifier.classify(429, "PerDay limit"))
        assertEquals(ErrorClass.RATE_LIMITED, CloudErrorClassifier.classify(429, "minute"))
        assertEquals(ErrorClass.OVERLOADED, CloudErrorClassifier.classify(503, ""))
        assertEquals(ErrorClass.SERVER_ERROR, CloudErrorClassifier.classify(500, ""))
        assertEquals(ErrorClass.BAD_REQUEST, CloudErrorClassifier.classify(400, "bad"))
        assertEquals(ErrorClass.NETWORK_ERROR, CloudErrorClassifier.classify(null, "x"))
    }

    @Test fun health_dailyExhausted() {
        var now = 0L
        val h = ChainHealth { now }
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.RATE_LIMITED_DAILY)
        assertFalse(h.isAvailable(e))
        now = 6 * 60 * 60 * 1000L + 1
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_rateLimited_cap() {
        var now = 0L
        val h = ChainHealth { now }
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
        val h = ChainHealth { now }
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.RATE_LIMITED, null)
        now = 19_000L
        assertFalse(h.isAvailable(e))
        now = 20_001L
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_circuit() {
        var now = 0L
        val h = ChainHealth { now }
        val e = DefaultCloudChain.entries()[0]
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        assertFalse(h.isAvailable(e))
        now = 5 * 60 * 1000L + 1
        assertTrue(h.isAvailable(e)) // half-open
    }

    @Test fun health_circuit_success_resets() {
        var now = 0L
        val h = ChainHealth { now }
        val e = DefaultCloudChain.entries()[0]
        repeat(2) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        h.recordSuccess(e)
        now = 0
        repeat(3) { h.recordFailure(e, ErrorClass.OVERLOADED) }
        assertFalse(h.isAvailable(e))
    }

    @Test fun health_auth_disabled_until_reset() {
        var now = 0L
        val h = ChainHealth { now }
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.AUTH_OR_REGION)
        assertFalse(h.isAvailable(e))
        h.reset(e.providerId)
        assertTrue(h.isAvailable(e))
    }

    @Test fun health_snapshot() {
        val h = ChainHealth { 0L }
        val e = DefaultCloudChain.entries()[0]
        h.recordFailure(e, ErrorClass.OVERLOADED)
        val snap = h.snapshot()
        assertEquals(1, snap.size)
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
