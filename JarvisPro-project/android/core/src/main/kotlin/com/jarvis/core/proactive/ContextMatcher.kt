package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorKind
import com.jarvis.core.behavior.PatternFamily
import com.jarvis.core.companion.ContextSnapshot
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/**
 * Stage 7 B: сравнивает BehaviorCandidate.entities с текущим [ContextSnapshot].
 * Не читает raw Android events / ObservationWindow.
 * Temporal — только entities["hour"] (ROUTINE / TEMPORAL / когда hour задан).
 */
class ContextMatcher(
    private val weights: ContextMatchWeights = ContextMatchWeights(),
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
) {

    fun match(candidate: BehaviorCandidate, snapshot: ContextSnapshot): ContextMatchResult {
        val w = weights.normalized()
        val ctx = snapshot.context
        val entities = candidate.entities
        val matched = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        var weighted = 0f
        var weightSum = 0f

        val alreadySatisfied = isAlreadySatisfied(candidate, snapshot)

        val trigger = entities["trigger"]
        if (trigger != null) {
            weightSum += w.trigger
            when (trigger) {
                "HEADPHONES_CONNECTED" -> {
                    if (ctx.headphonesConnected) {
                        weighted += w.trigger
                        matched += "headphones"
                    } else {
                        mismatched += "headphones"
                    }
                }
                "BATTERY_LOW" -> {
                    val pct = ctx.batteryPercent
                    if (pct != null && pct <= 20) {
                        weighted += w.trigger
                        matched += "battery_low"
                    } else {
                        mismatched += "battery_low"
                    }
                }
                else -> {
                    // неизвестный trigger — не учитываем в знаменателе
                    weightSum -= w.trigger
                }
            }
        }

        val follow = entities["follow"] ?: entities["to"]
        if (!follow.isNullOrBlank()) {
            weightSum += w.followApp
            when {
                alreadySatisfied -> mismatched += "follow_already_open"
                ctx.currentApp.isNullOrBlank() -> {
                    weighted += w.followApp * 0.55f
                    matched += "follow_pending:$follow"
                }
                else -> {
                    // другое приложение на FG — типичный момент для предложения follow
                    weighted += w.followApp * 0.85f
                    matched += "follow_pending:$follow"
                }
            }
        }

        val hourEntity = entities["hour"]?.toIntOrNull()
        if (hourEntity != null && shouldUseHour(candidate)) {
            weightSum += w.hour
            val nowHour = hourOf(snapshot.takenAtMillis)
            val diff = hourDiff(nowHour, hourEntity)
            if (diff <= w.hourTolerance) {
                val factor = 1f - (diff.toFloat() / (w.hourTolerance + 1))
                weighted += w.hour * factor.coerceIn(0.4f, 1f)
                matched += "hour~$hourEntity(now=$nowHour)"
            } else {
                mismatched += "hour~$hourEntity(now=$nowHour)"
            }
        }

        val pkgOnly = entities["package"]
        if (pkgOnly != null && trigger == null && follow == null) {
            weightSum += w.packageEntity
            if (alreadySatisfied) {
                mismatched += "package_already_fg"
            } else {
                // Stage 7.5: package-only FREQUENCY — слабый сигнал, не matched сам по себе.
                // Иначе high-freq apps (Telegram и т.п.) спамят QUESTION без реального контекста.
                weighted += w.packageEntity * 0.35f
                mismatched += "package_only_weak:$pkgOnly"
            }
        }

        if (weightSum <= 0f) {
            return ContextMatchResult(
                matched = false,
                score = 0f,
                matchedSignals = matched,
                mismatchSignals = mismatched + "no_matchable_entities",
                alreadySatisfied = alreadySatisfied,
            )
        }

        val score = (weighted / weightSum).coerceIn(0f, 1f)
        val headphonesRequired = trigger == "HEADPHONES_CONNECTED"
        val headphonesOk = !headphonesRequired || "headphones" in matched
        // matched требует сильный сигнал: trigger и/или follow (не голый package FREQUENCY)
        val hasStrongSignal =
            matched.any { it == "headphones" || it == "battery_low" || it.startsWith("follow_pending") } ||
                matched.any { it.startsWith("hour~") && (trigger != null || follow != null) }
        val matchedFinal = !alreadySatisfied && headphonesOk && hasStrongSignal && score >= 0.40f

        return ContextMatchResult(
            matched = matchedFinal,
            score = if (alreadySatisfied) min(score, 0.25f) else score,
            matchedSignals = matched,
            mismatchSignals = mismatched,
            alreadySatisfied = alreadySatisfied,
        )
    }

    /**
     * v1 conservative: follow / to / package уже равен currentApp.
     */
    fun isAlreadySatisfied(candidate: BehaviorCandidate, snapshot: ContextSnapshot): Boolean {
        val expected = candidate.entities["follow"]
            ?: candidate.entities["to"]
            ?: candidate.entities["package"]
            ?: return false
        val current = snapshot.context.currentApp ?: return false
        return appsCompatible(current, expected)
    }

    private fun shouldUseHour(candidate: BehaviorCandidate): Boolean {
        if (candidate.entities["hour"] == null) return false
        return candidate.family == PatternFamily.TEMPORAL ||
            candidate.kind == BehaviorKind.ROUTINE
    }

    private fun appsCompatible(current: String, expected: String): Boolean {
        if (current == expected) return true
        if (expected.length >= 4 && current.contains(expected, ignoreCase = true)) return true
        if (current.length >= 4 && expected.contains(current, ignoreCase = true)) return true
        return false
    }

    private fun hourOf(ts: Long): Int {
        val cal = Calendar.getInstance(timeZone())
        cal.timeInMillis = ts
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun hourDiff(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 24 - d)
    }
}
