package com.jarvis.core.behavior

import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 6.5 store: merge evidence, IGNORE cooldown, persistable snapshot.
 */
class BehaviorStore {
    private val candidates = ConcurrentHashMap<String, BehaviorCandidate>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    /** patternKey → ignoreUntilMillis (IGNORE ≠ DISABLE). */
    private val ignoreUntil = ConcurrentHashMap<String, Long>()

    fun upsertSuggestions(list: List<BehaviorCandidate>, nowMillis: Long = System.currentTimeMillis()) {
        for (c in list) {
            if (c.patternKey in disabled) continue
            val prev = candidates[c.patternKey]
            if (prev?.status == BehaviorCandidateStatus.ACCEPTED) continue
            if (prev?.status == BehaviorCandidateStatus.DISABLED) continue

            val mergedIds = ((prev?.supportingEvidenceIds ?: emptyList()) + c.supportingEvidenceIds)
                .distinct()
                .takeLast(24)
            val newEvidence = mergedIds.size > (prev?.supportingEvidenceIds?.size ?: 0)
            val mergedConf = ConfidenceModel.merge(prev?.confidence, c.confidence, newEvidence)
            val neg = maxOf(prev?.negativeEvidenceCount ?: 0, c.negativeEvidenceCount)
            val status = when {
                prev?.status == BehaviorCandidateStatus.REJECTED -> BehaviorCandidateStatus.REJECTED
                else -> BehaviorCandidateStatus.SUGGEST_ONLY
            }
            candidates[c.patternKey] = c.copy(
                confidence = mergedConf,
                evidenceCount = maxOf(prev?.evidenceCount ?: 0, c.evidenceCount, mergedIds.size),
                supportingEvidenceIds = mergedIds,
                firstObservedMillis = minOf(
                    prev?.firstObservedMillis ?: c.firstObservedMillis,
                    c.firstObservedMillis,
                ),
                lastObservedMillis = maxOf(
                    prev?.lastObservedMillis ?: 0L,
                    c.lastObservedMillis,
                ),
                negativeEvidenceCount = neg,
                uniqueDays = maxOf(prev?.uniqueDays ?: 0, c.uniqueDays),
                status = if (c.status == BehaviorCandidateStatus.STALE) {
                    BehaviorCandidateStatus.STALE
                } else {
                    status
                },
            )
            // clear expired ignore
            val until = ignoreUntil[c.patternKey]
            if (until != null && nowMillis >= until) ignoreUntil.remove(c.patternKey)
        }
    }

    fun suggestions(nowMillis: Long = System.currentTimeMillis()): List<BehaviorCandidate> =
        candidates.values
            .filter { it.status == BehaviorCandidateStatus.SUGGEST_ONLY }
            .filter { it.confidence >= 0.45f }
            .filter { c ->
                val until = ignoreUntil[c.patternKey] ?: return@filter true
                nowMillis >= until
            }
            .sortedByDescending { it.confidence }

    /**
     * Read-only candidates for Stage 7 proactive (ignore cooldown проверяет CooldownPolicy).
     *
     * Источник — in-memory [candidates] map (bounded BehaviorStore), не SQL/full history scan.
     * На каждом tick: filter + sort top-N по confidence, cap = 24.
     * Не включает DISABLED / STALE; не меняет semantics IGNORE / REJECT / DISABLE.
     */
    fun activeCandidates(nowMillis: Long = System.currentTimeMillis()): List<BehaviorCandidate> =
        candidates.values
            .filter {
                it.status == BehaviorCandidateStatus.SUGGEST_ONLY ||
                    it.status == BehaviorCandidateStatus.ACCEPTED
            }
            .filter { it.status != BehaviorCandidateStatus.STALE }
            .filter { it.patternKey !in disabled }
            .filter { it.confidence >= 0.40f }
            .sortedByDescending { it.confidence }
            .take(ACTIVE_CANDIDATES_LIMIT)

    fun all(): List<BehaviorCandidate> = candidates.values.sortedByDescending { it.confidence }

    fun get(patternKey: String): BehaviorCandidate? = candidates[patternKey]

    fun disabledKeys(): Set<String> = disabled.toSet()
    fun rejectedKeys(): Set<String> = rejected.toSet()
    fun ignoreUntilMap(): Map<String, Long> = ignoreUntil.toMap()

    fun applyFeedback(
        patternKey: String,
        feedback: BehaviorFeedback,
        nowMillis: Long = System.currentTimeMillis(),
        ignoreCooldownMs: Long = 6 * 3_600_000L, // 6h
    ): BehaviorCandidate? {
        val cur = candidates[patternKey] ?: return null
        return when (feedback) {
            BehaviorFeedback.ACCEPT -> {
                ignoreUntil.remove(patternKey)
                val next = cur.copy(status = BehaviorCandidateStatus.ACCEPTED)
                candidates[patternKey] = next
                next
            }
            BehaviorFeedback.IGNORE -> {
                // Не сейчас — cooldown, НЕ блокируем навсегда
                ignoreUntil[patternKey] = nowMillis + ignoreCooldownMs
                cur.copy(status = BehaviorCandidateStatus.SUGGEST_ONLY).also {
                    candidates[patternKey] = it
                }
            }
            BehaviorFeedback.REJECT -> {
                rejected.add(patternKey)
                ignoreUntil.remove(patternKey)
                val next = cur.copy(
                    status = BehaviorCandidateStatus.REJECTED,
                    confidence = (cur.confidence - 0.2f).coerceAtLeast(0f),
                    negativeEvidenceCount = cur.negativeEvidenceCount + 1,
                )
                candidates[patternKey] = next
                next
            }
            BehaviorFeedback.DISABLE -> {
                disabled.add(patternKey)
                rejected.remove(patternKey)
                ignoreUntil.remove(patternKey)
                val next = cur.copy(status = BehaviorCandidateStatus.DISABLED)
                candidates[patternKey] = next
                next
            }
        }
    }

    fun loadDisabled(keys: Collection<String>) {
        disabled.addAll(keys)
    }

    fun loadRejected(keys: Collection<String>) {
        rejected.addAll(keys)
    }

    fun loadIgnoreUntil(map: Map<String, Long>) {
        ignoreUntil.putAll(map)
    }

    fun replaceAll(candidatesIn: List<BehaviorCandidate>) {
        candidates.clear()
        for (c in candidatesIn) candidates[c.patternKey] = c
    }

    fun snapshot(): BehaviorStoreSnapshot = BehaviorStoreSnapshot(
        candidates = all(),
        disabledKeys = disabledKeys(),
        rejectedKeys = rejectedKeys(),
        ignoreUntil = ignoreUntilMap(),
    )

    fun restore(snapshot: BehaviorStoreSnapshot) {
        clearForTests()
        replaceAll(snapshot.candidates)
        loadDisabled(snapshot.disabledKeys)
        loadRejected(snapshot.rejectedKeys)
        loadIgnoreUntil(snapshot.ignoreUntil)
    }

    fun clearForTests() {
        candidates.clear()
        disabled.clear()
        rejected.clear()
        ignoreUntil.clear()
    }

    companion object {
        const val ACTIVE_CANDIDATES_LIMIT = 24
    }
}

data class BehaviorStoreSnapshot(
    val candidates: List<BehaviorCandidate>,
    val disabledKeys: Set<String>,
    val rejectedKeys: Set<String>,
    val ignoreUntil: Map<String, Long>,
)
