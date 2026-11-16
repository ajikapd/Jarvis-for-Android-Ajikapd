package com.jarvis.core.behavior

import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.memory.CompanionMemoryService
import com.jarvis.core.memory.MemoryKind

/**
 * Stage 6 / 6.5 orchestrator:
 * sanitize → dedupe → window → detectors → decay → policy → store.
 * Нет ToolCall / autonomy. ACCEPT → Memory flow отдельно.
 */
class BehaviorLearningService(
    private val store: BehaviorStore = BehaviorStore(),
    private val policy: BehaviorPolicy = BehaviorPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onStoreChanged: (() -> Unit)? = null,
) {
    @Volatile
    private var lastEvaluateAt: Long = 0L

    fun store(): BehaviorStore = store

    /**
     * Incremental: не чаще чем раз в [minIntervalMs], если нет force.
     */
    fun evaluate(
        recent: List<PhoneObservation>,
        force: Boolean = false,
        minIntervalMs: Long = 2_000L,
    ): List<BehaviorCandidate> {
        val now = clock()
        if (!force && now - lastEvaluateAt < minIntervalMs && store.suggestions(now).isNotEmpty()) {
            return store.suggestions(now)
        }
        lastEvaluateAt = now

        val cleaned = BehaviorObservationSanitizer.dedupeEvidence(
            BehaviorObservationSanitizer.sanitizeForLearning(recent),
        )
        val window = ObservationWindow.from(cleaned, nowMillis = now)
        // Detectors must use the same clock as the window (tests + decay consistency)
        val patterns = PatternDetector(clock = clock).detect(window)
        val routines = RoutineDetector(clock = clock).detect(window)
        val raw = patterns + routines
        val decayed = raw.map { BehaviorDecay.apply(it, now) }
        val filtered = policy.filter(
            decayed,
            disabledKeys = store.disabledKeys(),
            rejectedKeys = store.rejectedKeys(),
        )
        store.upsertSuggestions(filtered, nowMillis = now)
        onStoreChanged?.invoke()
        return store.suggestions(now)
    }

    fun feedback(
        patternKey: String,
        feedback: BehaviorFeedback,
        memory: CompanionMemoryService? = null,
    ): BehaviorCandidate? {
        val keys = relatedPatternKeys(patternKey)
        var primary: BehaviorCandidate? = null
        for (key in keys) {
            val updated = store.applyFeedback(key, feedback, nowMillis = clock()) ?: continue
            if (primary == null) primary = updated
        }
        if (primary == null) return null
        onStoreChanged?.invoke()
        if (feedback == BehaviorFeedback.ACCEPT && memory != null) {
            val kind = if (primary.kind == BehaviorKind.ROUTINE) {
                MemoryKind.ROUTINE
            } else {
                MemoryKind.SEMANTIC
            }
            memory.rememberUserStatement(
                statement = primary.description,
                kind = kind,
                confidence = primary.confidence.coerceAtLeast(0.9f),
                sensitivity = Sensitivity.PRIVATE,
            )
        }
        return primary
    }

    /** ROUTINE и SEQUENCE по одному trigger→follow делят feedback. */
    private fun relatedPatternKeys(patternKey: String): List<String> {
        val keys = linkedSetOf(patternKey)
        val all = store.all().map { it.patternKey }
        when {
            patternKey.startsWith("routine:HEADPHONES→") -> {
                val rest = patternKey.removePrefix("routine:HEADPHONES→")
                val pkg = rest.substringBefore("@")
                keys += "seq:HEADPHONES→$pkg"
                all.filter { it.startsWith("routine:HEADPHONES→$pkg") }.forEach { keys += it }
            }
            patternKey.startsWith("seq:HEADPHONES→") -> {
                val pkg = patternKey.removePrefix("seq:HEADPHONES→")
                keys += all.filter { it.startsWith("routine:HEADPHONES→$pkg") }
            }
        }
        return keys.toList()
    }
}
