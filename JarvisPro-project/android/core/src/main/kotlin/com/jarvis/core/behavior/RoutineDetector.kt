package com.jarvis.core.behavior

import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation

/**
 * RoutineDetector — отдельно от PatternDetector.
 * Routine = устойчивая повторяющаяся последовательность + временная регулярность.
 */
class RoutineDetector(
    private val minEvidence: Int = 4,
    private val minUniqueDays: Int = 3,
    private val sequenceGapMs: Long = 5 * 60_000L,
    private val hourSlack: Int = 2,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun detect(window: ObservationWindow): List<BehaviorCandidate> {
        val now = clock()
        return detectHeadphonesToAppRoutine(window, now) +
            detectAppSequenceRoutine(window, now)
    }

    private fun detectHeadphonesToAppRoutine(
        window: ObservationWindow,
        now: Long,
    ): List<BehaviorCandidate> {
        val obs = window.observations
        val byPkg = mutableMapOf<String, MutableList<PhoneObservation>>()
        for (i in obs.indices) {
            val a = obs[i]
            if (a.type != ObservationType.HEADPHONES_CONNECTED) continue
            val b = obs.drop(i + 1).firstOrNull {
                it.type == ObservationType.APP_FOREGROUND &&
                    !it.packageName.isNullOrBlank() &&
                    !BehaviorObservationSanitizer.isNoisyPackage(it.packageName) &&
                    it.timestampMillis - a.timestampMillis in 1..sequenceGapMs
            } ?: continue
            byPkg.getOrPut(b.packageName!!) { mutableListOf() }.add(b)
        }
        return byPkg.mapNotNull { (pkg, list) ->
            val perDay = list.distinctBy { it.id }
                .groupBy { PatternDetector.dayKey(it.timestampMillis) }
                .mapValues { (_, v) -> v.maxByOrNull { it.timestampMillis }!! }
                .values
                .toList()
            if (perDay.size < minEvidence) return@mapNotNull null
            val days = PatternDetector.uniqueDays(perDay.map { it.timestampMillis })
            if (days < minUniqueDays) return@mapNotNull null
            val hours = perDay.map { PatternDetector.hourOf(it.timestampMillis) }
            val modeHour = hours.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return@mapNotNull null
            val inBand = hours.count { absHourDiff(it, modeHour) <= hourSlack }
            if (inBand < minEvidence) return@mapNotNull null
            val regularity = inBand.toFloat() / perDay.size
            val conf = ConfidenceModel.score(
                evidenceCount = perDay.size,
                uniqueDays = days,
                lastObservedMillis = perDay.maxOf { it.timestampMillis },
                nowMillis = now,
                sourceReliability = 0.9f,
                regularityScore = regularity,
            )
            BehaviorCandidate(
                kind = BehaviorKind.ROUTINE,
                family = PatternFamily.SEQUENCE,
                description = "Привычка: после наушников около $modeHour:00 запускается $pkg",
                confidence = conf,
                evidenceCount = perDay.size,
                firstObservedMillis = perDay.minOf { it.timestampMillis },
                lastObservedMillis = perDay.maxOf { it.timestampMillis },
                supportingEvidenceIds = perDay.map { it.id }.takeLast(16),
                patternKey = "routine:HEADPHONES→$pkg@$modeHour",
                entities = mapOf(
                    "trigger" to "HEADPHONES_CONNECTED",
                    "follow" to pkg,
                    "hour" to modeHour.toString(),
                ),
                uniqueDays = days,
                why = "${perDay.size} повторов в $days днях; час ≈$modeHour (±$hourSlack)",
            )
        }
    }

    private fun detectAppSequenceRoutine(
        window: ObservationWindow,
        now: Long,
    ): List<BehaviorCandidate> {
        val apps = window.observations.filter {
            it.type == ObservationType.APP_FOREGROUND && !it.packageName.isNullOrBlank()
        }
        val transitions = mutableMapOf<String, MutableList<Long>>()
        for (i in 0 until apps.size - 1) {
            val a = apps[i].packageName!!
            val b = apps[i + 1].packageName!!
            if (a == b) continue
            val dt = apps[i + 1].timestampMillis - apps[i].timestampMillis
            if (dt !in 1..sequenceGapMs) continue
            val key = "$a→$b"
            transitions.getOrPut(key) { mutableListOf() }.add(apps[i + 1].timestampMillis)
        }
        return transitions.mapNotNull { (key, times) ->
            if (times.size < minEvidence) return@mapNotNull null
            val days = PatternDetector.uniqueDays(times)
            if (days < minUniqueDays) return@mapNotNull null
            val hours = times.map { PatternDetector.hourOf(it) }
            val modeHour = hours.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
            val regularity = PatternDetector.regularityByHour(times)
            if (regularity < 0.4f) return@mapNotNull null
            val conf = ConfidenceModel.score(
                evidenceCount = times.size,
                uniqueDays = days,
                lastObservedMillis = times.maxOrNull() ?: now,
                nowMillis = now,
                sourceReliability = 0.85f,
                regularityScore = regularity,
            )
            val (from, to) = key.split("→")
            BehaviorCandidate(
                kind = BehaviorKind.ROUTINE,
                family = PatternFamily.SEQUENCE,
                description = "Привычка: после $from часто открывается $to (≈$modeHour:00)",
                confidence = conf,
                evidenceCount = times.size,
                firstObservedMillis = times.minOrNull() ?: now,
                lastObservedMillis = times.maxOrNull() ?: now,
                patternKey = "routine:APP:$key@$modeHour",
                entities = mapOf("from" to from, "to" to to, "hour" to modeHour.toString()),
                uniqueDays = days,
                why = "${times.size} переходов $from→$to за $days дн.; regularity=${
                    String.format(java.util.Locale.US, "%.2f", regularity)
                }",
            )
        }
    }

    private fun absHourDiff(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b)
        return minOf(d, 24 - d)
    }
}
