package com.jarvis.core.proactive

/**
 * Pure codec для [ProactivityState]. Versioned, без sensitive raw text.
 * Malformed / unknown version → initial state (не throw).
 */
object ProactivitySnapshotCodec {

    private const val VERSION = "v1"

    fun encode(state: ProactivityState): String = buildString {
        appendLine(VERSION)
        appendLine("lastDecisionAt=${state.lastDecisionAtMillis}")
        appendLine("lastDecisionType=${state.lastDecisionType?.name.orEmpty()}")
        appendLine("globalCooldownUntil=${state.globalCooldownUntilMillis}")
        appendLine("budgetTokens=${state.budgetTokens}")
        appendLine("budgetLastRefillAt=${state.budgetLastRefillAtMillis}")
        appendLine("suggestionCount=${state.suggestionCount}")
        appendLine("questionCount=${state.questionCount}")
        appendLine("confirmationCount=${state.confirmationCount}")
        appendLine("recordedNoOpCount=${state.recordedNoOpCount}")
        appendLine("behaviorCooldown=${encodeMap(state.behaviorCooldownUntil)}")
        appendLine("fingerprintCooldown=${encodeMap(state.fingerprintCooldownUntil)}")
        appendLine("rejectCooldown=${encodeMap(state.rejectCooldownUntil)}")
    }

    fun decode(raw: String): ProactivityState {
        if (raw.isBlank()) return ProactivityState.initial()
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || lines[0] != VERSION) return ProactivityState.initial()

        var lastDecisionAt = 0L
        var lastDecisionType: ProactiveDecisionType? = null
        var globalCooldownUntil = 0L
        var budgetTokens = ProactivityConfig.DEFAULT.budgetMaxTokens
        var budgetLastRefillAt = 0L
        var suggestionCount = 0
        var questionCount = 0
        var confirmationCount = 0
        var recordedNoOpCount = 0
        var behaviorCooldown = emptyMap<String, Long>()
        var fingerprintCooldown = emptyMap<String, Long>()
        var rejectCooldown = emptyMap<String, Long>()

        for (line in lines.drop(1)) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "lastDecisionAt" -> lastDecisionAt = value.toLongOrNull() ?: 0L
                "lastDecisionType" -> lastDecisionType = value.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { ProactiveDecisionType.valueOf(it) }.getOrNull() }
                "globalCooldownUntil" -> globalCooldownUntil = value.toLongOrNull() ?: 0L
                "budgetTokens" -> budgetTokens = (value.toIntOrNull()
                    ?: ProactivityConfig.DEFAULT.budgetMaxTokens)
                    .coerceIn(0, ProactivityConfig.DEFAULT.budgetMaxTokens * 4)
                    .coerceAtMost(ProactivityConfig.DEFAULT.budgetMaxTokens)
                "budgetLastRefillAt" -> budgetLastRefillAt = value.toLongOrNull() ?: 0L
                "suggestionCount" -> suggestionCount = (value.toIntOrNull() ?: 0).coerceAtLeast(0)
                "questionCount" -> questionCount = (value.toIntOrNull() ?: 0).coerceAtLeast(0)
                "confirmationCount" -> confirmationCount = (value.toIntOrNull() ?: 0).coerceAtLeast(0)
                "recordedNoOpCount" -> recordedNoOpCount = (value.toIntOrNull() ?: 0).coerceAtLeast(0)
                "behaviorCooldown" -> behaviorCooldown = decodeMap(value, ProactivityConfig.DEFAULT.maxBehaviorCooldowns)
                "fingerprintCooldown" -> fingerprintCooldown = decodeMap(value, ProactivityConfig.DEFAULT.maxFingerprints)
                "rejectCooldown" -> rejectCooldown = decodeMap(value, ProactivityConfig.DEFAULT.maxRejectCooldowns)
            }
        }

        return ProactivityState(
            lastDecisionAtMillis = lastDecisionAt.coerceAtLeast(0L),
            lastDecisionType = lastDecisionType,
            globalCooldownUntilMillis = globalCooldownUntil.coerceAtLeast(0L),
            behaviorCooldownUntil = behaviorCooldown,
            fingerprintCooldownUntil = fingerprintCooldown,
            rejectCooldownUntil = rejectCooldown,
            budgetTokens = budgetTokens,
            budgetLastRefillAtMillis = budgetLastRefillAt.coerceAtLeast(0L),
            suggestionCount = suggestionCount,
            questionCount = questionCount,
            confirmationCount = confirmationCount,
            recordedNoOpCount = recordedNoOpCount,
        )
    }

    private fun encodeMap(map: Map<String, Long>): String =
        map.entries.joinToString(",") { "${esc(it.key)}@${it.value}" }

    private fun decodeMap(raw: String, max: Int): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        val out = linkedMapOf<String, Long>()
        for (part in raw.split(',')) {
            if (part.isBlank()) continue
            val at = part.lastIndexOf('@')
            if (at <= 0) continue
            val k = unesc(part.substring(0, at))
            val v = part.substring(at + 1).toLongOrNull() ?: continue
            if (k.isNotEmpty()) out[k] = v
        }
        return ProactivityState.boundMap(out, max)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace(",", "\\,").replace("@", "\\@").replace("\n", "\\n")

    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }
}
