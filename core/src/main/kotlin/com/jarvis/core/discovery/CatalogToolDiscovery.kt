package com.jarvis.core.discovery

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.execution.CatalogToolRegistry
import com.jarvis.core.execution.ToolAvailability
import com.jarvis.core.execution.ToolCatalogEntry
import com.jarvis.core.execution.ToolExecutionLocality

/**
 * Stage 9: Capability / Tool Discovery над [CatalogToolRegistry].
 *
 * - возвращает **только релевантный subset**;
 * - учитывает semantic score, permissions, availability, risk, online/offline;
 * - не вызывает Tool.execute;
 * - не является Planner / Executor.
 */
class CatalogToolDiscovery(
    private val catalog: CatalogToolRegistry,
    private val defaultLimit: Int = DiscoveryContext.DEFAULT_LIMIT,
) {

    fun discover(context: DiscoveryContext): DiscoveryResult {
        val limit = context.limit.coerceIn(1, DiscoveryContext.HARD_CAP)
        val all = catalog.catalogEntries()
        val eligible = all.filter { entry -> passesConstraints(entry, context) }
        val q = fold(context.query)

        val scored = if (q.isBlank()) {
            // Пустой query — только низкий risk + ALWAYS, урезанный subset (не весь каталог)
            eligible
                .filter {
                    it.riskLevel.ordinal <= RiskLevel.LOW.ordinal &&
                        it.availability == ToolAvailability.ALWAYS
                }
                .sortedBy { it.toolId }
                .take(limit.coerceAtMost(4))
                .map { entry ->
                    Scored(entry, 0.1f, listOf("blank_query_safe_subset"))
                }
        } else {
            eligible.mapNotNull { entry ->
                val (score, reasons) = score(q, entry)
                if (score <= 0f) null else Scored(entry, score, reasons)
            }.sortedByDescending { it.score }
                .take(limit)
        }

        val items = scored.map { it.toItem() }
        return DiscoveryResult(
            query = context.query,
            items = items,
            truncated = eligible.size > items.size,
            considered = eligible.size,
            notes = listOf(
                "catalog_size=${all.size}",
                "eligible=${eligible.size}",
                "returned=${items.size}",
                "limit=$limit",
            ),
        )
    }

    fun discover(query: String, limit: Int = defaultLimit): DiscoveryResult =
        discover(DiscoveryContext(query = query, limit = limit))

    private fun passesConstraints(entry: ToolCatalogEntry, ctx: DiscoveryContext): Boolean {
        if (ctx.requireCapability && entry.capabilityId.isNullOrBlank()) return false
        if (entry.riskLevel.ordinal > ctx.maxRisk.ordinal) return false
        if (ctx.excludeUnavailable && entry.availability == ToolAvailability.UNAVAILABLE) {
            return false
        }
        when (entry.availability) {
            ToolAvailability.NEEDS_ONLINE -> {
                if (ctx.isOnline == false) return false
            }
            ToolAvailability.NEEDS_PERMISSION -> {
                val granted = ctx.grantedPermissions
                if (granted != null) {
                    val required = entry.requiredPermissions.toSet()
                    if (required.isNotEmpty() && !granted.containsAll(required)) return false
                }
            }
            ToolAvailability.NEEDS_ACCESSIBILITY -> {
                val granted = ctx.grantedPermissions
                if (granted != null && "capability:ACCESSIBILITY" !in granted) return false
            }
            else -> Unit
        }
        // Permission filter even for ALWAYS tools that declare requiredPermissions
        val granted = ctx.grantedPermissions
        if (granted != null && entry.requiredPermissions.isNotEmpty()) {
            if (!granted.containsAll(entry.requiredPermissions)) return false
        }
        // Offline: remote-only tools out
        if (ctx.isOnline == false &&
            entry.locality == com.jarvis.core.execution.ToolExecutionLocality.REMOTE
        ) {
            return false
        }
        return true
    }

    private fun score(q: String, entry: ToolCatalogEntry): Pair<Float, List<String>> {
        var s = 0f
        val reasons = mutableListOf<String>()
        val idFold = fold(entry.toolId.replace('_', ' ').replace('.', ' '))
        val name = fold(entry.name)
        val desc = fold(entry.description)
        val cap = fold(entry.capabilityId.orEmpty().replace('_', ' '))
        val tags = entry.metadata.tags.joinToString(" ") { fold(it) }
        val params = entry.parameterNames.joinToString(" ") { fold(it) }

        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        for (t in tokens) {
            if (idFold.contains(t)) {
                s += 3f; reasons += "id:$t"
            }
            if (name.contains(t)) {
                s += 2.5f; reasons += "name:$t"
            }
            if (cap.contains(t)) {
                s += 2.2f; reasons += "cap:$t"
            }
            if (tags.contains(t)) {
                s += 2f; reasons += "tag:$t"
            }
            if (params.contains(t)) {
                s += 1f; reasons += "param:$t"
            }
            if (desc.contains(t)) {
                s += 0.8f
            }
        }
        for (syn in synonymBoost(q)) {
            if (tags.contains(syn) || idFold.contains(syn) || name.contains(syn) || cap.contains(syn)) {
                s += 2f
                reasons += "syn:$syn"
            }
        }
        // Prefer safer tools slightly when scores tie-break later
        if (entry.riskLevel == RiskLevel.SAFE) s += 0.05f
        return s to reasons.distinct().take(6)
    }

    private fun synonymBoost(q: String): List<String> = buildList {
        if (q.contains("фонар") || q.contains("свети") || q.contains("темно") || q.contains("свет")) {
            add("light"); add("flashlight"); add("фонарик"); add("device light")
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
        if (q.contains("настрой") || q.contains("wifi") || q.contains("bluetooth")) {
            add("setting"); add("system")
        }
    }

    private fun fold(s: String): String =
        s.lowercase()
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    private data class Scored(
        val entry: ToolCatalogEntry,
        val score: Float,
        val reasons: List<String>,
    ) {
        fun toItem() = DiscoveredItem(
            toolId = entry.toolId,
            capabilityId = entry.capabilityId,
            name = entry.name,
            description = entry.description,
            score = score,
            riskLevel = entry.riskLevel,
            requiredPermissions = entry.requiredPermissions,
            availability = entry.availability,
            source = entry.source,
            locality = entry.locality,
            hasExecutable = entry.hasExecutable,
            parameterNames = entry.parameterNames,
            reasons = reasons,
        )
    }
}

/**
 * Утилита: discovery никогда не должен вернуть весь каталог.
 */
object DiscoveryBounds {
    fun ensureNotFullCatalog(result: DiscoveryResult, catalogSize: Int) {
        require(result.items.size <= DiscoveryContext.HARD_CAP)
        if (catalogSize > DiscoveryContext.HARD_CAP) {
            require(result.items.size < catalogSize) {
                "Discovery leaked full catalog"
            }
        }
    }
}
