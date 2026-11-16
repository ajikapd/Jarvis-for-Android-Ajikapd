package com.jarvis.brain.aiprovider

/**
 * Stage 10.5: оценка сложности запроса для выбора провайдера.
 * Не autonomy / risk — только routing hint.
 */
enum class AIRequestComplexity {
    SIMPLE,
    COMPLEX,
}

/**
 * Контекст выбора AIProvider (legacy Stage 10.5 API).
 * V2-03: предпочтительно [AiTaskProfile]; [AIRouter.select] адаптирует.
 */
data class AIRouterContext(
    val isOnline: Boolean,
    val privacyRestricted: Boolean = false,
    /** При online + complex предпочитать cloud, если AVAILABLE. */
    val preferCloudWhenOnline: Boolean = false,
    val complexity: AIRequestComplexity = AIRequestComplexity.SIMPLE,
    /** Явный override (если provider AVAILABLE). */
    val preferredProviderId: String? = null,
)

enum class AIRouterReasonCode {
    PREFERRED,
    PRIVACY_LOCAL_ONLY,
    OFFLINE_LOCAL,
    ONLINE_COMPLEX_CLOUD,
    ONLINE_SIMPLE_LOCAL,
    ONLINE_SIMPLE_CLOUD_PREF,
    FALLBACK_LOCAL,
    FALLBACK_ANY_AVAILABLE,
    NONE_AVAILABLE,
    /** V2-03 */
    ONLINE_QUALITY,
    LATENCY_PRIORITY,
    STRUCTURED_LOCAL,
    STREAMING_CAPABLE,
    CLOUD_UNAVAILABLE,
    HEALTH_SKIP,
}

data class AIProviderSelection(
    val provider: AIProvider?,
    val reasonCode: AIRouterReasonCode,
    val reason: String,
    val fallbackUsed: Boolean = false,
    val considered: List<String> = emptyList(),
    /** V2-03 diagnostics */
    val modelId: String? = null,
    val selectionReason: String = reasonCode.name,
    val candidateProviders: List<String> = emptyList(),
    val candidateModels: List<String> = emptyList(),
    val providerHealth: String? = null,
    val profile: AiTaskProfile? = null,
    val attempt: Int = 1,
)

data class AIRouteAttempt(
    val attempt: Int,
    val providerId: String,
    val modelId: String?,
    val reason: String,
    val latencyMs: Long,
    val error: String?,
)

/**
 * V2-03 Hybrid AI Router: policy-aware provider/model selection.
 *
 * Semantic route (CHAT/PLAN/…) и AI provider route (cloud/local) — разные уровни.
 */
class AIRouter(
    private val registry: AIProviderRegistry,
    private val health: ProviderHealthTracker = ProviderHealthTracker(),
    private val metrics: ProviderMetricsStore = ProviderMetricsStore(),
    private val cloudFastModel: () -> String = { NaraRouterDefaults.MODEL_FAST },
    private val cloudQualityModel: () -> String = { NaraRouterDefaults.MODEL_QUALITY },
    private val localModelId: () -> String = { "local" },
) {
    val healthTracker: ProviderHealthTracker get() = health
    val metricsStore: ProviderMetricsStore get() = metrics

    @Volatile
    var lastAttempts: List<AIRouteAttempt> = emptyList()
        private set

    fun select(context: AIRouterContext): AIProviderSelection =
        select(AiTaskProfile.fromLegacy(context), context.preferredProviderId)

    fun select(
        profile: AiTaskProfile,
        preferredProviderId: String? = null,
    ): AIProviderSelection {
        val considered = mutableListOf<String>()
        val locals = registry.localProviders()
        val clouds = registry.cloudProviders()
        locals.forEach { considered += "${it.id}:${effectiveAvailability(it)}" }
        clouds.forEach { considered += "${it.id}:${effectiveAvailability(it)}" }

        val candidates = filterCandidates(profile, locals + clouds)
        val candidateIds = candidates.map { it.id }
        val candidateModels = candidates.map { resolveModelId(it, profile) }

        preferredProviderId?.let { prefId ->
            val pref = registry.find(prefId)
            if (pref != null && isUsable(pref, profile)) {
                if (profile.privacyLevel == PrivacyLevel.LOCAL_REQUIRED &&
                    pref.kind == AIProviderKind.CLOUD
                ) {
                    // blocked
                } else {
                    return selection(
                        pref, profile, AIRouterReasonCode.PREFERRED,
                        "Preferred provider $prefId",
                        considered, candidateIds, candidateModels,
                    )
                }
            }
        }

        if (profile.privacyLevel == PrivacyLevel.LOCAL_REQUIRED ||
            (profile.privacyLevel == PrivacyLevel.SENSITIVE && !profile.preferCloud)
        ) {
            val local = pickLocal(locals, profile)
            return if (local != null) {
                selection(
                    local, profile, AIRouterReasonCode.PRIVACY_LOCAL_ONLY,
                    "Privacy → local only",
                    considered, candidateIds, candidateModels,
                )
            } else {
                none(considered, candidateIds, "Privacy-restricted and no local provider", profile)
            }
        }

        if (!profile.isOnline) {
            val local = pickLocal(locals, profile)
            return if (local != null) {
                selection(
                    local, profile, AIRouterReasonCode.OFFLINE_LOCAL,
                    "Offline → local",
                    considered, candidateIds, candidateModels,
                )
            } else {
                none(considered, candidateIds, "Offline and no local provider", profile)
            }
        }

        // Capability: streaming required
        if (profile.streamingRequired) {
            val streamOk = candidates.filter { it.capabilities.supportsStreaming }
            if (streamOk.isNotEmpty()) {
                val best = rank(streamOk, profile).firstOrNull()
                if (best != null) {
                    return selection(
                        best, profile, AIRouterReasonCode.STREAMING_CAPABLE,
                        "Streaming required → ${best.id}",
                        considered, candidateIds, candidateModels,
                    )
                }
            }
            // Fall through to non-streaming with note in reason later
        }

        // Structured semantic: prefer fast local
        if (profile.taskType == AiTaskType.SEMANTIC_INTERPRETATION ||
            profile.structuredOutputRequired
        ) {
            val structured = candidates.filter {
                it.capabilities.supportsStructuredDecide &&
                    (it.kind == AIProviderKind.LOCAL || profile.preferCloud)
            }
            val localStruct = structured.firstOrNull { it.kind == AIProviderKind.LOCAL }
                ?: pickLocal(locals, profile)?.takeIf { it.capabilities.supportsStructuredDecide }
            if (localStruct != null && !profile.preferCloud) {
                return selection(
                    localStruct, profile, AIRouterReasonCode.STRUCTURED_LOCAL,
                    "Structured/semantic → local fast",
                    considered, candidateIds, candidateModels,
                )
            }
            val cloudStruct = structured.firstOrNull { it.kind == AIProviderKind.CLOUD }
            if (cloudStruct != null && profile.preferCloud) {
                return selection(
                    cloudStruct, profile, AIRouterReasonCode.ONLINE_SIMPLE_CLOUD_PREF,
                    "Structured + preferCloud → cloud",
                    considered, candidateIds, candidateModels,
                )
            }
            if (localStruct != null) {
                return selection(
                    localStruct, profile, AIRouterReasonCode.STRUCTURED_LOCAL,
                    "Structured → local",
                    considered, candidateIds, candidateModels,
                )
            }
        }

        // Latency priority: fastest suitable (when user did not prefer cloud)
        if (!profile.preferCloud &&
            profile.latencySensitivity == LatencySensitivity.HIGH &&
            profile.qualityRequirement == QualityRequirement.FAST
        ) {
            val ranked = rank(candidates, profile)
            val fastest = ranked.firstOrNull()
            if (fastest != null) {
                return selection(
                    fastest, profile, AIRouterReasonCode.LATENCY_PRIORITY,
                    "Latency priority → ${fastest.id}",
                    considered, candidateIds, candidateModels,
                )
            }
        }

        // Complex / reasoning / planning → strongest available
        if (profile.reasoningRequired ||
            profile.qualityRequirement == QualityRequirement.REASONING ||
            profile.qualityRequirement == QualityRequirement.QUALITY ||
            profile.taskType == AiTaskType.PLANNING ||
            profile.complexity == AIRequestComplexity.COMPLEX
        ) {
            val cloud = firstHealthyCloud(clouds, profile)
            if (cloud != null && (profile.preferCloud || profile.reasoningRequired ||
                    profile.taskType == AiTaskType.PLANNING)
            ) {
                return selection(
                    cloud, profile, AIRouterReasonCode.ONLINE_QUALITY,
                    "Online quality/reasoning → cloud",
                    considered, candidateIds, candidateModels,
                )
            }
            if (cloud != null && profile.preferCloud) {
                return selection(
                    cloud, profile, AIRouterReasonCode.ONLINE_COMPLEX_CLOUD,
                    "Online + complex → cloud",
                    considered, candidateIds, candidateModels,
                )
            }
            val local = pickLocal(locals, profile)
            if (local != null) {
                return selection(
                    local, profile, AIRouterReasonCode.FALLBACK_LOCAL,
                    "Cloud unavailable for quality task → local",
                    considered, candidateIds, candidateModels,
                    fallback = cloud == null && profile.preferCloud,
                )
            }
        }

        // Normal chat with preferCloud
        if (profile.preferCloud) {
            val cloud = firstHealthyCloud(clouds, profile)
            if (cloud != null) {
                return selection(
                    cloud, profile, AIRouterReasonCode.ONLINE_SIMPLE_CLOUD_PREF,
                    "Online + preferCloud → cloud",
                    considered, candidateIds, candidateModels,
                )
            }
            val local = pickLocal(locals, profile)
            if (local != null) {
                return selection(
                    local, profile, AIRouterReasonCode.CLOUD_UNAVAILABLE,
                    "Cloud unavailable → local fallback",
                    considered, candidateIds, candidateModels,
                    fallback = true,
                )
            }
        }

        // Default: local
        val local = pickLocal(locals, profile)
        if (local != null) {
            return selection(
                local, profile, AIRouterReasonCode.ONLINE_SIMPLE_LOCAL,
                "Online → local (default)",
                considered, candidateIds, candidateModels,
            )
        }

        val any = candidates.firstOrNull() ?: registry.available().firstOrNull()
        return if (any != null) {
            selection(
                any, profile, AIRouterReasonCode.FALLBACK_ANY_AVAILABLE,
                "Fallback any available ${any.id}",
                considered, candidateIds, candidateModels,
                fallback = true,
            )
        } else {
            none(considered, candidateIds, "No AI providers available", profile)
        }
    }

    fun complete(
        context: AIRouterContext,
        request: AICompletionRequest,
    ): Pair<AIProviderSelection, AICompletionResult> =
        complete(AiTaskProfile.fromLegacy(context, request.requestId), request)

    /**
     * Execute with policy selection + deterministic fallback (privacy-aware).
     */
    fun complete(
        profile: AiTaskProfile,
        request: AICompletionRequest,
        preferredProviderId: String? = null,
    ): Pair<AIProviderSelection, AICompletionResult> {
        val attempts = mutableListOf<AIRouteAttempt>()
        var selection = select(profile, preferredProviderId)
        var provider = selection.provider
        if (provider == null) {
            lastAttempts = attempts
            return selection to AICompletionResult(
                requestId = request.requestId,
                providerId = "none",
                status = AICompletionStatus.FAILURE,
                error = AIProviderError(
                    AIProviderErrorCategory.UNAVAILABLE,
                    "NO_PROVIDER",
                    selection.reason,
                ),
            )
        }

        var attempt = 1
        val tried = mutableSetOf<String>()
        while (provider != null && attempt <= MAX_ATTEMPTS) {
            tried += provider.id
            val modelId = resolveModelId(provider, profile)
            val enriched = request.copy(
                preferredModelId = modelId,
                taskType = profile.taskType,
                wantStructured = request.wantStructured || profile.structuredOutputRequired,
            )
            val result = provider.complete(enriched)
            attempts += AIRouteAttempt(
                attempt = attempt,
                providerId = provider.id,
                modelId = result.modelId ?: modelId,
                reason = selection.selectionReason,
                latencyMs = result.latencyMs,
                error = result.error?.code,
            )
            if (result.status == AICompletionStatus.SUCCESS) {
                health.recordSuccess(provider.id)
                metrics.record(
                    providerId = provider.id,
                    kind = provider.kind,
                    latencyMs = result.latencyMs,
                    success = true,
                    fallbackUsed = selection.fallbackUsed,
                    ttftMs = result.ttftMs,
                )
                lastAttempts = attempts
                val finalSel = selection.copy(
                    modelId = result.modelId ?: modelId,
                    attempt = attempt,
                    providerHealth = health.snapshot(provider.id),
                )
                logTrace(profile, finalSel, result)
                return finalSel to result.copy(modelId = result.modelId ?: modelId)
            }

            health.recordFailure(provider.id, result.error, result.latencyMs)
            metrics.record(
                providerId = provider.id,
                kind = provider.kind,
                latencyMs = result.latencyMs,
                success = false,
                fallbackUsed = selection.fallbackUsed,
            )

            if (!profile.fallbackAllowed) break
            // Privacy LOCAL_REQUIRED: never fall to cloud
            if (profile.privacyLevel == PrivacyLevel.LOCAL_REQUIRED) break

            val next = nextFallback(profile, tried)
            if (next == null) {
                lastAttempts = attempts
                val failSel = selection.copy(
                    modelId = modelId,
                    attempt = attempt,
                    providerHealth = health.snapshot(provider.id),
                    fallbackUsed = selection.fallbackUsed,
                )
                logTrace(profile, failSel, result)
                return failSel to result
            }
            selection = selection.copy(
                provider = next,
                reasonCode = AIRouterReasonCode.FALLBACK_LOCAL,
                reason = "Fallback after ${provider.id} error → ${next.id}",
                selectionReason = "FALLBACK_AFTER_ERROR",
                fallbackUsed = true,
                modelId = resolveModelId(next, profile),
                attempt = attempt + 1,
                providerHealth = health.snapshot(next.id),
            )
            provider = next
            attempt++
        }

        lastAttempts = attempts
        return selection to AICompletionResult(
            requestId = request.requestId,
            providerId = provider?.id ?: "none",
            status = AICompletionStatus.FAILURE,
            error = AIProviderError(
                AIProviderErrorCategory.UNAVAILABLE,
                "ALL_ATTEMPTS_FAILED",
                "All provider attempts failed",
            ),
        )
    }

    fun aggregateMetrics(): RouteAggregateMetrics = metrics.aggregate()

    // ── internals ────────────────────────────────────────────────

    private fun filterCandidates(
        profile: AiTaskProfile,
        all: List<AIProvider>,
    ): List<AIProvider> = all.filter { p ->
        if (!isUsable(p, profile)) return@filter false
        if (profile.privacyLevel == PrivacyLevel.LOCAL_REQUIRED &&
            p.kind == AIProviderKind.CLOUD
        ) {
            return@filter false
        }
        if (!profile.isOnline && p.capabilities.requiresNetwork) return@filter false
        if (profile.structuredOutputRequired &&
            !p.capabilities.supportsStructuredDecide
        ) {
            return@filter false
        }
        if (profile.contextSizeTokens > 0 &&
            p.capabilities.maxContextTokens > 0 &&
            profile.contextSizeTokens > p.capabilities.maxContextTokens * 2
        ) {
            // Soft: still allow, but deprioritize via rank
        }
        val quota = health.quota(p.id)
        if (quota == QuotaState.EXHAUSTED) return@filter false
        true
    }

    private fun isUsable(p: AIProvider, profile: AiTaskProfile): Boolean {
        val av = effectiveAvailability(p)
        if (av == AIProviderAvailability.UNAVAILABLE) return false
        if (p.kind == AIProviderKind.LOCAL) {
            return av == AIProviderAvailability.AVAILABLE ||
                av == AIProviderAvailability.DEGRADED
        }
        // Cloud: AVAILABLE only (DEGRADED health may still try after cooldown → DEGRADED ok)
        val hs = health.status(p.id)
        if (hs == ProviderHealthStatus.UNAVAILABLE) return false
        return av == AIProviderAvailability.AVAILABLE ||
            hs == ProviderHealthStatus.DEGRADED
    }

    private fun effectiveAvailability(p: AIProvider): AIProviderAvailability {
        val base = p.availability()
        val hs = health.status(p.id)
        return when {
            hs == ProviderHealthStatus.UNAVAILABLE -> AIProviderAvailability.UNAVAILABLE
            hs == ProviderHealthStatus.DEGRADED &&
                base == AIProviderAvailability.AVAILABLE -> AIProviderAvailability.DEGRADED
            else -> base
        }
    }

    private fun pickLocal(locals: List<AIProvider>, profile: AiTaskProfile): AIProvider? =
        rank(locals.filter { isUsable(it, profile) }, profile).firstOrNull()
            ?: locals.firstOrNull {
                val a = it.availability()
                a == AIProviderAvailability.AVAILABLE || a == AIProviderAvailability.DEGRADED
            }

    private fun firstHealthyCloud(clouds: List<AIProvider>, profile: AiTaskProfile): AIProvider? =
        rank(clouds.filter { isUsable(it, profile) }, profile).firstOrNull()

    private fun rank(list: List<AIProvider>, profile: AiTaskProfile): List<AIProvider> {
        return list.sortedByDescending { p ->
            var score = 0f
            // Health
            score += when (health.status(p.id)) {
                ProviderHealthStatus.AVAILABLE, ProviderHealthStatus.UNKNOWN -> 1f
                ProviderHealthStatus.DEGRADED -> 0.4f
                ProviderHealthStatus.UNAVAILABLE -> -10f
            }
            // Latency
            if (profile.latencySensitivity == LatencySensitivity.HIGH) {
                score += metrics.speedScore(p.id) * 2f
                if (p.kind == AIProviderKind.LOCAL) score += 0.3f
            }
            // Quality
            when (profile.qualityRequirement) {
                QualityRequirement.REASONING, QualityRequirement.QUALITY -> {
                    if (p.kind == AIProviderKind.CLOUD) score += 1.5f
                    score += when (p.capabilities.qualityTier) {
                        QualityRequirement.REASONING -> 1.2f
                        QualityRequirement.QUALITY -> 1f
                        QualityRequirement.BALANCED -> 0.4f
                        QualityRequirement.FAST -> 0.1f
                    }
                }
                QualityRequirement.FAST -> {
                    score += metrics.speedScore(p.id)
                    if (p.capabilities.speedTier == QualityRequirement.FAST) score += 0.8f
                    if (p.kind == AIProviderKind.LOCAL) score += 0.5f
                }
                QualityRequirement.BALANCED -> {
                    score += 0.3f
                    if (profile.preferCloud && p.kind == AIProviderKind.CLOUD) score += 0.6f
                    if (!profile.preferCloud && p.kind == AIProviderKind.LOCAL) score += 0.6f
                }
            }
            // Streaming match
            if (profile.streamingRequired && p.capabilities.supportsStreaming) score += 1f
            // Structured
            if (profile.structuredOutputRequired && p.capabilities.supportsStructuredDecide) {
                score += 0.5f
            }
            score
        }
    }

    private fun resolveModelId(provider: AIProvider, profile: AiTaskProfile): String {
        if (provider.kind == AIProviderKind.LOCAL) {
            return provider.capabilities.defaultModelId.ifBlank { localModelId() }
        }
        return when (profile.qualityRequirement) {
            QualityRequirement.REASONING, QualityRequirement.QUALITY ->
                cloudQualityModel().ifBlank {
                    provider.capabilities.defaultModelId.ifBlank { NaraRouterDefaults.MODEL_QUALITY }
                }
            QualityRequirement.FAST, QualityRequirement.BALANCED ->
                cloudFastModel().ifBlank {
                    provider.capabilities.defaultModelId.ifBlank { NaraRouterDefaults.MODEL_FAST }
                }
        }
    }

    private fun nextFallback(profile: AiTaskProfile, tried: Set<String>): AIProvider? {
        val pool = filterCandidates(profile, registry.all()).filter { it.id !in tried }
        return rank(pool, profile).firstOrNull()
    }

    private fun selection(
        provider: AIProvider,
        profile: AiTaskProfile,
        code: AIRouterReasonCode,
        reason: String,
        considered: List<String>,
        candidates: List<String>,
        models: List<String>,
        fallback: Boolean = false,
    ) = AIProviderSelection(
        provider = provider,
        reasonCode = code,
        reason = reason,
        fallbackUsed = fallback,
        considered = considered,
        modelId = resolveModelId(provider, profile),
        selectionReason = code.name,
        candidateProviders = candidates,
        candidateModels = models,
        providerHealth = health.snapshot(provider.id),
        profile = profile,
    )

    private fun none(
        considered: List<String>,
        candidates: List<String>,
        reason: String,
        profile: AiTaskProfile,
    ) = AIProviderSelection(
        provider = null,
        reasonCode = AIRouterReasonCode.NONE_AVAILABLE,
        reason = reason,
        considered = considered,
        selectionReason = AIRouterReasonCode.NONE_AVAILABLE.name,
        candidateProviders = candidates,
        profile = profile,
    )

    private fun logTrace(
        profile: AiTaskProfile,
        sel: AIProviderSelection,
        result: AICompletionResult,
    ) {
        runCatching {
            android.util.Log.i(
                "Jarvis.AIRouter",
                "req=${profile.requestId.ifBlank { result.requestId }} " +
                    "task=${profile.taskType} route=${profile.routeHint ?: "-"} " +
                    "provider=${sel.provider?.id ?: "-"} model=${sel.modelId ?: "-"} " +
                    "reason=${sel.selectionReason} fallback=${sel.fallbackUsed} " +
                    "health=${sel.providerHealth ?: "-"} " +
                    "candidates=${sel.candidateProviders.joinToString(",")} " +
                    "latency=${result.latencyMs}ms ttft=${result.ttftMs ?: "-"} " +
                    "streamReq=${profile.streamingRequired} streamUsed=${result.streamed} " +
                    "status=${result.status} attempt=${sel.attempt}",
            )
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
