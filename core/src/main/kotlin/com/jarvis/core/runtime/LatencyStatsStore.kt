package com.jarvis.core.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * V2-06: in-memory latency stats (not durable). For diagnostics / p50/p90.
 */
data class LatencySample(
    val requestId: String,
    val route: String,
    val e2eMs: Long?,
    val firstAudioMs: Long?,
    val firstUsefulMs: Long?,
    val aiCompletionMs: Long?,
    val realTtftMs: Long?,
    val ttftKind: String,
    val warmState: String,
    val coldStart: Boolean,
    val cancelled: Boolean,
    val provider: String?,
    val model: String?,
    val atMillis: Long = System.currentTimeMillis(),
)

data class LatencyPercentiles(
    val count: Int,
    val p50: Long?,
    val p90: Long?,
    val p95: Long?,
    val min: Long?,
    val max: Long?,
)

object LatencyStatsStore {
    private const val MAX = 64
    private val samples = CopyOnWriteArrayList<LatencySample>()
    private val byRoute = ConcurrentHashMap<String, CopyOnWriteArrayList<LatencySample>>()

    @Volatile
    var last: LatencySample? = null
        private set

    fun record(sample: LatencySample) {
        last = sample
        samples.add(sample)
        while (samples.size > MAX) samples.removeAt(0)
        val list = byRoute.getOrPut(sample.route) { CopyOnWriteArrayList() }
        list.add(sample)
        while (list.size > MAX) list.removeAt(0)
    }

    fun recordFrom(trace: RequestTrace) {
        record(
            LatencySample(
                requestId = trace.requestId,
                route = trace.route.name,
                e2eMs = trace.e2eMs ?: trace.totalLatencyMs.takeIf { it > 0 },
                firstAudioMs = trace.firstAudioMs,
                firstUsefulMs = trace.firstUsefulMs,
                aiCompletionMs = trace.aiCompletionMs ?: trace.aiLatencyMs,
                realTtftMs = if (trace.aiTtftKind == TtftKind.REAL.name) trace.aiTtftMs else null,
                ttftKind = trace.aiTtftKind ?: TtftKind.NOT_AVAILABLE.name,
                warmState = trace.warmState ?: WarmState.UNKNOWN.name,
                coldStart = trace.coldStart == true,
                cancelled = trace.cancelled == true,
                provider = trace.provider,
                model = trace.model,
            ),
        )
    }

    fun percentiles(route: String? = null, selector: (LatencySample) -> Long?): LatencyPercentiles {
        val src = if (route == null) samples else byRoute[route].orEmpty()
        val values = src.mapNotNull(selector).sorted()
        if (values.isEmpty()) {
            return LatencyPercentiles(0, null, null, null, null, null)
        }
        fun pct(p: Double): Long {
            val idx = ((values.size - 1) * p).toInt().coerceIn(0, values.lastIndex)
            return values[idx]
        }
        return LatencyPercentiles(
            count = values.size,
            p50 = pct(0.50),
            p90 = pct(0.90),
            p95 = pct(0.95),
            min = values.first(),
            max = values.last(),
        )
    }

    fun summaryLines(): List<String> {
        val lastSample = last
        val audio = percentiles { it.firstAudioMs }
        val e2e = percentiles { it.e2eMs }
        val ai = percentiles { it.aiCompletionMs }
        val lines = mutableListOf<String>()
        lines += "latency last route=${lastSample?.route ?: "-"} " +
            "e2e=${lastSample?.e2eMs ?: "-"} audio=${lastSample?.firstAudioMs ?: "-"} " +
            "useful=${lastSample?.firstUsefulMs ?: "-"} " +
            "ai=${lastSample?.aiCompletionMs ?: "-"} ttft=${lastSample?.realTtftMs ?: "n/a"} " +
            "ttftKind=${lastSample?.ttftKind ?: "-"} warm=${lastSample?.warmState ?: "-"}"
        lines += "latency audio n=${audio.count} p50=${audio.p50 ?: "-"} p90=${audio.p90 ?: "-"} p95=${audio.p95 ?: "-"}"
        lines += "latency e2e n=${e2e.count} p50=${e2e.p50 ?: "-"} p90=${e2e.p90 ?: "-"}"
        lines += "latency aiCompletion n=${ai.count} p50=${ai.p50 ?: "-"} p90=${ai.p90 ?: "-"}"
        for (routeName in listOf("FAST_SKILL", "TEMPLATE", "CHAT", "PLAN", "CLARIFY")) {
            val r = percentiles(routeName) { it.firstAudioMs }
            if (r.count > 0) {
                lines += "latency $routeName audio n=${r.count} p50=${r.p50} p90=${r.p90}"
            }
        }
        return lines
    }

    fun clear() {
        samples.clear()
        byRoute.clear()
        last = null
    }
}
