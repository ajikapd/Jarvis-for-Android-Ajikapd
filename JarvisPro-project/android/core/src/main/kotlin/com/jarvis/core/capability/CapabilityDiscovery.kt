package com.jarvis.core.capability

import com.jarvis.core.common.model.RiskLevel

/**
 * Stage 9 filters для legacy CapabilityDiscovery (обратная совместимость).
 * null-поля = не фильтровать.
 */
data class CapabilityDiscoveryConstraints(
    val grantedPermissions: Set<String>? = null,
    val isOnline: Boolean? = null,
    val maxRisk: RiskLevel = RiskLevel.BLOCKED,
)

/**
 * Динамический discovery: в LLM / planner попадает только релевантный subset.
 * Не отдаём весь каталог на каждый запрос.
 *
 * Stage 9: добавлены constraints (permissions / online / risk).
 */
class CapabilityDiscovery(
    private val registry: CapabilityRegistry,
    private val maxResults: Int = 6,
) {

    fun discover(
        query: String,
        limit: Int = maxResults,
        constraints: CapabilityDiscoveryConstraints = CapabilityDiscoveryConstraints(),
    ): List<CapabilityDescriptor> {
        val capped = limit.coerceIn(1, HARD_CAP)
        val q = fold(query)
        val pool = registry.all().filter { passesConstraints(it, constraints) }

        if (q.isBlank()) {
            return pool
                .filter {
                    it.riskLevel.ordinal <= RiskLevel.LOW.ordinal &&
                        it.availability == CapabilityAvailability.ALWAYS
                }
                .take(capped.coerceAtMost(4))
        }

        val scored = pool.map { cap ->
            Scored(cap, score(q, cap))
        }.filter { it.score > 0f }
            .sortedByDescending { it.score }

        if (scored.isEmpty()) {
            return pool
                .filter {
                    it.riskLevel.ordinal <= RiskLevel.LOW.ordinal &&
                        it.availability == CapabilityAvailability.ALWAYS
                }
                .take(capped.coerceAtMost(4))
        }
        return scored.take(capped).map { it.descriptor }
    }

    fun discoverSummaries(
        query: String,
        limit: Int = maxResults,
        constraints: CapabilityDiscoveryConstraints = CapabilityDiscoveryConstraints(),
    ): List<CapabilitySummary> =
        discover(query, limit, constraints).map { it.toSummary() }

    private fun passesConstraints(
        cap: CapabilityDescriptor,
        c: CapabilityDiscoveryConstraints,
    ): Boolean {
        if (cap.riskLevel.ordinal > c.maxRisk.ordinal) return false
        when (cap.availability) {
            CapabilityAvailability.NEEDS_ONLINE -> if (c.isOnline == false) return false
            CapabilityAvailability.NEEDS_PERMISSION -> {
                val granted = c.grantedPermissions ?: return true
                if (cap.permissions.isNotEmpty() && !granted.containsAll(cap.permissions)) {
                    return false
                }
            }
            CapabilityAvailability.NEEDS_ACCESSIBILITY -> {
                val granted = c.grantedPermissions ?: return true
                if ("capability:ACCESSIBILITY" !in granted) return false
            }
            else -> Unit
        }
        val granted = c.grantedPermissions
        if (granted != null && cap.permissions.isNotEmpty()) {
            if (!granted.containsAll(cap.permissions)) return false
        }
        return true
    }

    private fun score(q: String, cap: CapabilityDescriptor): Float {
        var s = 0f
        val idFold = fold(cap.id.replace('_', ' ').replace('.', ' '))
        val title = fold(cap.title)
        val desc = fold(cap.description)
        val tags = cap.tags.joinToString(" ") { fold(it) }
        val examples = cap.examples.joinToString(" ") { fold(it) }
        val ops = cap.operations.joinToString(" ") { fold(it) }

        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        for (t in tokens) {
            if (idFold.contains(t)) s += 3f
            if (title.contains(t)) s += 2.5f
            if (tags.contains(t)) s += 2f
            if (ops.contains(t)) s += 1.5f
            if (examples.contains(t)) s += 2f
            if (desc.contains(t)) s += 0.8f
        }
        for (ex in cap.examples) {
            val e = fold(ex)
            if (e.isNotBlank() && (q.contains(e) || e.contains(q))) s += 4f
        }
        for (syn in synonymBoost(q)) {
            if (tags.contains(syn) || idFold.contains(syn) || title.contains(syn)) s += 2f
        }
        return s
    }

    private fun synonymBoost(q: String): List<String> = buildList {
        if (q.contains("фонар") || q.contains("свети") || q.contains("темно") || q.contains("свет")) {
            add("light"); add("flashlight"); add("фонарик")
        }
        if (q.contains("громк") || q.contains("тише") || q.contains("звук") || q.contains("volume")) {
            add("volume"); add("audio"); add("громкость")
        }
        if (q.contains("ярк") || q.contains("brightness") || q.contains("экран")) {
            add("brightness"); add("яркость")
        }
        if (q.contains("погод") || q.contains("градус") || q.contains("weather")) {
            add("weather"); add("погода")
        }
        if (q.contains("музык") || q.contains("песн") || q.contains("трек") || q.contains("пауза")) {
            add("music"); add("media")
        }
        if (q.contains("звон") || q.contains("набери") || q.contains("call")) {
            add("call"); add("phone")
        }
        if (q.contains("смс") || q.contains("sms") || q.contains("сообщен")) {
            add("sms"); add("message")
        }
        if (q.contains("телеграм") || q.contains("telegram") || q.contains("тг")) {
            add("telegram")
        }
        if (q.contains("открой") || q.contains("запусти") || q.contains("приложение")) {
            add("launcher"); add("app")
        }
        if (q.contains("таймер") || q.contains("будильник") || q.contains("напоми")) {
            add("timer")
        }
        if (q.contains("вики") || q.contains("расскажи") || q.contains("что такое") || q.contains("кто такой")) {
            add("knowledge"); add("wiki")
        }
        if (q.contains("настрой") || q.contains("wifi") || q.contains("bluetooth") || q.contains("wifi")) {
            add("setting"); add("system")
        }
        if (q.contains("нажми") || q.contains("тапни") || q.contains("свайп") || q.contains("на экран")) {
            add("ui"); add("screen")
        }
    }

    private fun fold(s: String): String =
        s.lowercase()
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    private data class Scored(val descriptor: CapabilityDescriptor, val score: Float)

    companion object {
        const val HARD_CAP = 12
    }
}
