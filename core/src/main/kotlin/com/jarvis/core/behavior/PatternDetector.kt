package com.jarvis.core.behavior

import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneObservation
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * PatternDetector — без ML. Temporal / Sequence / Frequency / Co-occurrence / Context.
 * Не путать с [RoutineDetector].
 */
class PatternDetector(
    private val minEvidence: Int = 3,
    private val sequenceGapMs: Long = 5 * 60_000L,
    private val coOccurGapMs: Long = 3 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun detect(window: ObservationWindow): List<BehaviorCandidate> {
        val now = clock()
        val out = mutableListOf<BehaviorCandidate>()
        out += detectSequences(window, now)
        out += detectFrequency(window, now)
        out += detectTemporal(window, now)
        out += detectCoOccurrence(window, now)
        out += detectContextBattery(window, now)
        return out.distinctBy { it.patternKey }
    }

    private fun detectSequences(window: ObservationWindow, now: Long): List<BehaviorCandidate> {
        val obs = window.observations
        data class Follow(val hpId: String, val app: PhoneObservation)
        val follows = mutableListOf<Follow>()
        for (i in obs.indices) {
            val a = obs[i]
            if (a.type != ObservationType.HEADPHONES_CONNECTED) continue
            val b = obs.drop(i + 1).firstOrNull {
                it.type == ObservationType.APP_FOREGROUND &&
                    !it.packageName.isNullOrBlank() &&
                    !BehaviorObservationSanitizer.isNoisyPackage(it.packageName) &&
                    it.timestampMillis - a.timestampMillis in 1..sequenceGapMs
            } ?: continue
            follows += Follow(a.id, b)
        }
        // Dedup: same headphones event counted once
        val uniqueFollows = follows.distinctBy { it.hpId }
        val byPkg = uniqueFollows.groupBy { it.app.packageName!! }
        val totalSessions = uniqueFollows.size
        return byPkg.mapNotNull { (pkg, list) ->
            val deduped = list.distinctBy { it.app.id }.map { it.app }
            // Не больше одного evidence на календарный день (анти-spam)
            val perDay = deduped.groupBy { dayKey(it.timestampMillis) }
                .mapValues { (_, v) -> v.maxByOrNull { it.timestampMillis }!! }
                .values
                .toList()
            if (perDay.size < minEvidence) return@mapNotNull null
            val days = uniqueDays(perDay.map { it.timestampMillis })
            val negative = (totalSessions - list.size).coerceAtLeast(0)
            val conf = ConfidenceModel.score(
                evidenceCount = perDay.size,
                uniqueDays = days,
                lastObservedMillis = perDay.maxOf { it.timestampMillis },
                nowMillis = now,
                sourceReliability = 0.88f,
                negativeEvidenceCount = negative,
                regularityScore = regularityByHour(perDay.map { it.timestampMillis }),
            )
            val key = "seq:HEADPHONES→$pkg"
            BehaviorCandidate(
                kind = BehaviorKind.PATTERN,
                family = PatternFamily.SEQUENCE,
                description = "После подключения наушников часто открывается $pkg",
                confidence = conf,
                evidenceCount = perDay.size,
                firstObservedMillis = perDay.minOf { it.timestampMillis },
                lastObservedMillis = perDay.maxOf { it.timestampMillis },
                supportingEvidenceIds = perDay.map { it.id }.takeLast(16),
                patternKey = key,
                entities = mapOf(
                    "trigger" to "HEADPHONES_CONNECTED",
                    "follow" to pkg,
                    "negative" to negative.toString(),
                ),
                uniqueDays = days,
                negativeEvidenceCount = negative,
                why = "${perDay.size} подтв. / $negative наруш. за $days дн.; окно ≤${sequenceGapMs / 60_000} мин",
            )
        }
    }

    private fun detectFrequency(window: ObservationWindow, now: Long): List<BehaviorCandidate> {
        val byPkg = window.observations
            .filter {
                it.type == ObservationType.APP_FOREGROUND &&
                    !it.packageName.isNullOrBlank() &&
                    !BehaviorObservationSanitizer.isNoisyPackage(it.packageName)
            }
            .groupBy { it.packageName!! }
        return byPkg.mapNotNull { (pkg, list) ->
            val deduped = list.distinctBy { it.id }
            val dayCount = deduped.map { dayKey(it.timestampMillis) }.toSet().size
            if (deduped.size < minEvidence + 1) return@mapNotNull null
            if (dayCount < 2 && deduped.size < 6) return@mapNotNull null
            val conf = ConfidenceModel.score(
                evidenceCount = deduped.size,
                uniqueDays = dayCount,
                lastObservedMillis = deduped.maxOf { it.timestampMillis },
                nowMillis = now,
                sourceReliability = 0.85f,
                regularityScore = regularityByHour(deduped.map { it.timestampMillis }),
            )
            BehaviorCandidate(
                kind = BehaviorKind.PATTERN,
                family = PatternFamily.FREQUENCY,
                description = "Приложение $pkg открывается часто",
                confidence = conf,
                evidenceCount = deduped.size,
                firstObservedMillis = deduped.minOf { it.timestampMillis },
                lastObservedMillis = deduped.maxOf { it.timestampMillis },
                supportingEvidenceIds = deduped.map { it.id }.takeLast(16),
                patternKey = "freq:APP:$pkg",
                entities = mapOf("package" to pkg),
                uniqueDays = dayCount,
                why = "${deduped.size} открытий за $dayCount дн.",
            )
        }
    }

    private fun detectTemporal(window: ObservationWindow, now: Long): List<BehaviorCandidate> {
        val byHourPkg = mutableMapOf<String, MutableList<PhoneObservation>>()
        for (o in window.observations) {
            if (o.type != ObservationType.APP_FOREGROUND || o.packageName.isNullOrBlank()) continue
            if (BehaviorObservationSanitizer.isNoisyPackage(o.packageName)) continue
            val hour = hourOf(o.timestampMillis)
            val key = "temporal:$hour:${o.packageName}"
            byHourPkg.getOrPut(key) { mutableListOf() }.add(o)
        }
        return byHourPkg.mapNotNull { (key, list) ->
            if (list.size < minEvidence) return@mapNotNull null
            val days = uniqueDays(list.map { it.timestampMillis })
            if (days < 2) return@mapNotNull null
            val hour = key.split(":")[1]
            val pkg = key.substringAfterLast(":")
            val conf = ConfidenceModel.score(
                evidenceCount = list.size,
                uniqueDays = days,
                lastObservedMillis = list.maxOf { it.timestampMillis },
                nowMillis = now,
                sourceReliability = 0.85f,
                regularityScore = 0.75f,
            )
            BehaviorCandidate(
                kind = BehaviorKind.PATTERN,
                family = PatternFamily.TEMPORAL,
                description = "Около $hour:00 часто открывается $pkg",
                confidence = conf,
                evidenceCount = list.size,
                firstObservedMillis = list.minOf { it.timestampMillis },
                lastObservedMillis = list.maxOf { it.timestampMillis },
                supportingEvidenceIds = list.map { it.id }.takeLast(16),
                patternKey = key,
                entities = mapOf("hour" to hour, "package" to pkg),
                uniqueDays = days,
                why = "${list.size} раз около $hour:00 в $days разных днях",
            )
        }
    }

    private fun detectCoOccurrence(window: ObservationWindow, now: Long): List<BehaviorCandidate> {
        val apps = window.observations.filter {
            it.type == ObservationType.APP_FOREGROUND &&
                !it.packageName.isNullOrBlank() &&
                !BehaviorObservationSanitizer.isNoisyPackage(it.packageName)
        }
        val pairCounts = mutableMapOf<String, MutableList<Long>>()
        for (i in apps.indices) {
            for (j in i + 1 until apps.size) {
                val a = apps[i]
                val b = apps[j]
                val dt = abs(b.timestampMillis - a.timestampMillis)
                if (dt < 1L || dt > coOccurGapMs) continue
                val p1 = a.packageName!!
                val p2 = b.packageName!!
                if (p1 == p2) continue
                val key = listOf(p1, p2).sorted().joinToString("|")
                pairCounts.getOrPut("co:$key") { mutableListOf() }.add(minOf(a.timestampMillis, b.timestampMillis))
            }
        }
        return pairCounts.mapNotNull { (key, times) ->
            val distinct = times.distinct().size
            if (distinct < minEvidence) return@mapNotNull null
            val pkgs = key.removePrefix("co:").split("|")
            val days = uniqueDays(times)
            val conf = ConfidenceModel.score(
                evidenceCount = distinct,
                uniqueDays = days,
                lastObservedMillis = times.maxOrNull() ?: now,
                nowMillis = now,
                sourceReliability = 0.85f,
                regularityScore = 0.65f,
            )
            BehaviorCandidate(
                kind = BehaviorKind.PATTERN,
                family = PatternFamily.CO_OCCURRENCE,
                description = "${pkgs[0]} и ${pkgs[1]} часто открываются рядом",
                confidence = conf,
                evidenceCount = distinct,
                firstObservedMillis = times.minOrNull() ?: now,
                lastObservedMillis = times.maxOrNull() ?: now,
                patternKey = key,
                entities = mapOf("a" to pkgs[0], "b" to pkgs[1]),
                uniqueDays = days,
                why = "$distinct совместных появлений (≤${coOccurGapMs / 60_000} мин) за $days дн.",
            )
        }
    }

    private fun detectContextBattery(window: ObservationWindow, now: Long): List<BehaviorCandidate> {
        val obs = window.observations
        val hits = mutableListOf<PhoneObservation>()
        for (i in obs.indices) {
            val bat = obs[i]
            if (bat.type != ObservationType.BATTERY_CHANGED) continue
            val pct = bat.entities["percent"]?.toIntOrNull() ?: continue
            if (pct > 20) continue
            val follow = obs.drop(i + 1).firstOrNull {
                it.type == ObservationType.APP_FOREGROUND &&
                    (it.packageName?.contains("settings") == true ||
                        it.packageName?.contains("securitycenter") == true) &&
                    it.timestampMillis - bat.timestampMillis in 1..sequenceGapMs
            } ?: continue
            hits += follow
        }
        if (hits.size < minEvidence) return emptyList()
        val days = uniqueDays(hits.map { it.timestampMillis })
        val conf = ConfidenceModel.score(
            evidenceCount = hits.size,
            uniqueDays = days,
            lastObservedMillis = hits.maxOf { it.timestampMillis },
            nowMillis = now,
            sourceReliability = 0.8f,
            regularityScore = 0.5f,
        )
        return listOf(
            BehaviorCandidate(
                kind = BehaviorKind.PATTERN,
                family = PatternFamily.CONTEXT,
                description = "При низком заряде часто открываются настройки",
                confidence = conf,
                evidenceCount = hits.size,
                firstObservedMillis = hits.minOf { it.timestampMillis },
                lastObservedMillis = hits.maxOf { it.timestampMillis },
                supportingEvidenceIds = hits.map { it.id }.takeLast(12),
                patternKey = "ctx:BATTERY_LOW→SETTINGS",
                entities = mapOf("trigger" to "BATTERY_LOW", "follow" to "settings"),
                uniqueDays = days,
                why = "${hits.size} раз после battery≤20% за $days дн.",
            ),
        )
    }

    companion object {
        fun uniqueDays(timestamps: List<Long>, tz: TimeZone = TimeZone.getDefault()): Int {
            return timestamps.map { dayKey(it, tz) }.toSet().size
        }

        fun dayKey(ts: Long, tz: TimeZone = TimeZone.getDefault()): String {
            val cal = Calendar.getInstance(tz)
            cal.timeInMillis = ts
            return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        }

        fun hourOf(ts: Long, tz: TimeZone = TimeZone.getDefault()): Int {
            val cal = Calendar.getInstance(tz)
            cal.timeInMillis = ts
            return cal.get(Calendar.HOUR_OF_DAY)
        }

        fun regularityByHour(timestamps: List<Long>): Float {
            if (timestamps.size < 2) return 0.3f
            val hours = timestamps.map { hourOf(it) }
            val mode = hours.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 1
            return (mode.toFloat() / timestamps.size).coerceIn(0.2f, 1f)
        }
    }
}
