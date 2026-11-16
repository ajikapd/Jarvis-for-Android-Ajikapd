package com.jarvis.brain.aiprovider

/**
 * V2-03: профиль AI-задачи для Hybrid AI Router.
 * Не путать с [com.jarvis.core.runtime.RouteDecision] (semantic route).
 * Router решает provider/model, а не FAST_SKILL/CHAT/PLAN.
 */
enum class AiTaskType {
    CHAT,
    PLANNING,
    SEMANTIC_INTERPRETATION,
    STRUCTURED,
    GENERAL,
}

enum class QualityRequirement {
    FAST,
    BALANCED,
    QUALITY,
    REASONING,
}

enum class PrivacyLevel {
    NORMAL,
    SENSITIVE,
    LOCAL_REQUIRED,
}

enum class LatencySensitivity {
    LOW,
    NORMAL,
    HIGH,
}

enum class QuotaState {
    AVAILABLE,
    NEAR_LIMIT,
    RATE_LIMITED,
    EXHAUSTED,
    UNKNOWN,
}

enum class ProviderHealthStatus {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}

/**
 * Метаданные конкретной модели (не жёстко привязаны к одному provider).
 */
data class ModelDescriptor(
    val providerId: String,
    val modelId: String,
    val qualityTier: QualityRequirement = QualityRequirement.BALANCED,
    val speedTier: QualityRequirement = QualityRequirement.BALANCED,
    val contextLimit: Int = 0,
    val streaming: Boolean = false,
    val structuredOutput: Boolean = true,
    val onlineOnly: Boolean = false,
    val privacyClass: PrivacyLevel = PrivacyLevel.NORMAL,
)

data class AiTaskProfile(
    val taskType: AiTaskType = AiTaskType.CHAT,
    /** Semantic route name for telemetry only (CHAT/PLAN/…). */
    val routeHint: String? = null,
    val complexity: AIRequestComplexity = AIRequestComplexity.SIMPLE,
    val reasoningRequired: Boolean = false,
    val privacyLevel: PrivacyLevel = PrivacyLevel.NORMAL,
    val contextSizeTokens: Int = 0,
    val streamingRequired: Boolean = false,
    val latencySensitivity: LatencySensitivity = LatencySensitivity.NORMAL,
    val qualityRequirement: QualityRequirement = QualityRequirement.BALANCED,
    val offlineAllowed: Boolean = true,
    val fallbackAllowed: Boolean = true,
    val structuredOutputRequired: Boolean = false,
    /** User preference — not an absolute order. */
    val preferCloud: Boolean = false,
    val isOnline: Boolean = true,
    val requestId: String = "",
) {
    val privacyRestricted: Boolean
        get() = privacyLevel == PrivacyLevel.SENSITIVE ||
            privacyLevel == PrivacyLevel.LOCAL_REQUIRED

    companion object {
        fun fromLegacy(context: AIRouterContext, requestId: String = ""): AiTaskProfile =
            AiTaskProfile(
                taskType = when (context.complexity) {
                    AIRequestComplexity.COMPLEX -> AiTaskType.CHAT
                    AIRequestComplexity.SIMPLE -> AiTaskType.CHAT
                },
                complexity = context.complexity,
                reasoningRequired = context.complexity == AIRequestComplexity.COMPLEX,
                privacyLevel = if (context.privacyRestricted) {
                    PrivacyLevel.LOCAL_REQUIRED
                } else {
                    PrivacyLevel.NORMAL
                },
                qualityRequirement = when (context.complexity) {
                    AIRequestComplexity.COMPLEX -> QualityRequirement.QUALITY
                    AIRequestComplexity.SIMPLE -> QualityRequirement.BALANCED
                },
                preferCloud = context.preferCloudWhenOnline,
                isOnline = context.isOnline,
                requestId = requestId,
            )

        fun forChat(
            isOnline: Boolean,
            preferCloud: Boolean,
            complexity: AIRequestComplexity,
            privacy: PrivacyLevel = PrivacyLevel.NORMAL,
            requestId: String = "",
            userTextLen: Int = 0,
        ): AiTaskProfile {
            val reasoning = complexity == AIRequestComplexity.COMPLEX || userTextLen > 180
            return AiTaskProfile(
                taskType = AiTaskType.CHAT,
                routeHint = "CHAT",
                complexity = if (reasoning) AIRequestComplexity.COMPLEX else complexity,
                reasoningRequired = reasoning,
                privacyLevel = privacy,
                contextSizeTokens = (userTextLen / 3).coerceAtLeast(32),
                latencySensitivity = when {
                    preferCloud -> LatencySensitivity.NORMAL
                    userTextLen in 1..39 -> LatencySensitivity.HIGH
                    else -> LatencySensitivity.NORMAL
                },
                qualityRequirement = when {
                    reasoning -> QualityRequirement.QUALITY
                    !preferCloud && complexity == AIRequestComplexity.SIMPLE && userTextLen in 1..39 ->
                        QualityRequirement.FAST
                    else -> QualityRequirement.BALANCED
                },
                preferCloud = preferCloud,
                isOnline = isOnline,
                requestId = requestId,
                streamingRequired = preferCloud && isOnline &&
                    privacy != PrivacyLevel.LOCAL_REQUIRED &&
                    privacy != PrivacyLevel.SENSITIVE,
            )
        }

        fun forSemanticInterpretation(
            isOnline: Boolean,
            preferCloud: Boolean = false,
            requestId: String = "",
        ): AiTaskProfile = AiTaskProfile(
            taskType = AiTaskType.SEMANTIC_INTERPRETATION,
            routeHint = null,
            complexity = AIRequestComplexity.SIMPLE,
            reasoningRequired = false,
            privacyLevel = PrivacyLevel.NORMAL,
            streamingRequired = false,
            latencySensitivity = LatencySensitivity.HIGH,
            qualityRequirement = QualityRequirement.FAST,
            structuredOutputRequired = true,
            // Classification: prefer local unless user forces cloud and local missing
            preferCloud = preferCloud,
            isOnline = isOnline,
            requestId = requestId,
            fallbackAllowed = true,
        )

        fun forPlanning(
            isOnline: Boolean,
            preferCloud: Boolean,
            requestId: String = "",
        ): AiTaskProfile = AiTaskProfile(
            taskType = AiTaskType.PLANNING,
            routeHint = "PLAN",
            complexity = AIRequestComplexity.COMPLEX,
            reasoningRequired = true,
            qualityRequirement = QualityRequirement.REASONING,
            latencySensitivity = LatencySensitivity.LOW,
            preferCloud = preferCloud,
            isOnline = isOnline,
            requestId = requestId,
        )
    }
}
