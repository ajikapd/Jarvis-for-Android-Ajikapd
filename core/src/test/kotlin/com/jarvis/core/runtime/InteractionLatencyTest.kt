package com.jarvis.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionLatencyTest {

    @Test
    fun realTtftOnlyWhenKindReal() {
        val lat = InteractionLatency("r1", anchorElapsedMs = 1000L)
        lat.llmRequestStartAt = 1100L
        lat.llmFirstTokenAt = 1250L
        lat.llmCompleteAt = 5000L
        lat.ttftKind = TtftKind.NOT_AVAILABLE
        assertNull(lat.realTtftMs())
        assertEquals(3900L, lat.aiCompletionMs())

        lat.ttftKind = TtftKind.REAL
        assertEquals(150L, lat.realTtftMs())
    }

    @Test
    fun applyLatencyDoesNotPublishFakeTtft() {
        val lat = InteractionLatency("r2", anchorElapsedMs = 0L)
        lat.llmRequestStartAt = 10L
        lat.llmCompleteAt = 4010L
        lat.ttftKind = TtftKind.NOT_AVAILABLE
        lat.firstUsefulAt = 4020L
        lat.ttsFirstAudioAt = 4500L
        lat.interactionCompleteAt = 5000L

        val trace = RequestTrace("r2", 0L, "VOICE")
        trace.applyLatency(lat)
        assertNull(trace.aiTtftMs)
        assertEquals(TtftKind.NOT_AVAILABLE.name, trace.aiTtftKind)
        assertEquals(4000L, trace.aiCompletionMs)
        assertEquals(4500L, trace.firstAudioMs)
        assertEquals(4020L, trace.firstUsefulMs)
    }

    @Test
    fun generationGateCancelsStale() {
        val gate = InteractionGenerationGate()
        val g1 = gate.begin()
        assertFalse(gate.isStale(g1))
        gate.cancelCurrent()
        assertTrue(gate.isStale(g1))
        val g2 = gate.begin()
        assertFalse(gate.isStale(g2))
    }

    @Test
    fun immediateAckSkipsFastPaths() {
        assertNull(
            ImmediateAckPolicy.acknowledgementFor(RouteDecision.FAST_SKILL, "включи фонарик"),
        )
        assertNull(
            ImmediateAckPolicy.acknowledgementFor(RouteDecision.TEMPLATE, "привет"),
        )
        assertNotNull(
            ImmediateAckPolicy.acknowledgementFor(
                RouteDecision.CHAT,
                "Объясни подробно почему небо голубое и как рассеивается свет",
            ),
        )
    }

    @Test
    fun latencyStatsPercentiles() {
        LatencyStatsStore.clear()
        LatencyStatsStore.record(
            LatencySample(
                requestId = "a",
                route = "CHAT",
                e2eMs = 100,
                firstAudioMs = 80,
                firstUsefulMs = 70,
                aiCompletionMs = 60,
                realTtftMs = null,
                ttftKind = TtftKind.NOT_AVAILABLE.name,
                warmState = WarmState.WARM.name,
                coldStart = false,
                cancelled = false,
                provider = "cloud.llm",
                model = "x",
            ),
        )
        LatencyStatsStore.record(
            LatencySample(
                requestId = "b",
                route = "CHAT",
                e2eMs = 200,
                firstAudioMs = 180,
                firstUsefulMs = 170,
                aiCompletionMs = 160,
                realTtftMs = null,
                ttftKind = TtftKind.NOT_AVAILABLE.name,
                warmState = WarmState.WARM.name,
                coldStart = false,
                cancelled = false,
                provider = "cloud.llm",
                model = "x",
            ),
        )
        val p = LatencyStatsStore.percentiles("CHAT") { it.firstAudioMs }
        assertEquals(2, p.count)
        assertNotNull(p.p50)
        assertTrue(LatencyStatsStore.summaryLines().isNotEmpty())
        LatencyStatsStore.clear()
    }

    @Test
    fun traceSummaryContainsLatencyFields() {
        val t = RequestTrace("x", 1L, "TEXT")
        t.aiTtftKind = TtftKind.NOT_AVAILABLE.name
        t.firstAudioMs = 900
        t.e2eMs = 900
        val line = t.summaryLine()
        assertTrue(line.contains("ttftKind=NOT_AVAILABLE"))
        assertTrue(line.contains("audio=900"))
    }
}
