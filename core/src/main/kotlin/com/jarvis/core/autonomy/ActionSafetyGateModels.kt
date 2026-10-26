package com.jarvis.core.autonomy

/**
 * Stage 8D: статус финального SafetyGate.
 * PASS = можно передать в Executor (Executor пока не реализуется).
 * BLOCK = fail-closed отказ. Никогда не ослабляет DENY Policy.
 */
enum class ActionSafetyGateStatus {
    PASS,
    BLOCK,
}

/**
 * Stage 8D: причины блокировки. Не путать с [AutonomyReasonCode] (Policy).
 */
enum class ActionSafetyFailureReason {
    DENY_VERDICT,
    NO_ACTION_VERDICT,
    MISSING_ACTION_REQUEST,
    MISSING_RISK_ASSESSMENT,
    RISK_ASSESSMENT_MISMATCH,
    INVALID_ARGUMENTS,
    UNKNOWN_PARAMETER,
    FORBIDDEN_PARAMETER,
    MISSING_PERMISSION,
    PERMISSIONS_UNSPECIFIED,
    STALE_CONTEXT,
    MISSING_CONTEXT_FRESHNESS,
    EXPIRED_DECISION,
    MISSING_DECISION_VALIDITY,
    MALFORMED_TIMESTAMP,
    CLOCK_ANOMALY,
    MISSING_CONFIRMATION,
    INVALID_CONFIRMATION,
    EXPIRED_CONFIRMATION,
    CONFIRMATION_ACTION_MISMATCH,
    CONFIRMATION_ALREADY_USED,
    MISSING_POLICY_BINDING,
    PLAN_INTEGRITY_FAILURE,
    ACTION_KIND_MODIFIED,
    ARGUMENTS_MODIFIED,
    TARGET_MODIFIED,
    UNKNOWN_SECURITY_STATE,
}

/**
 * Свежесть ContextSnapshot (отдельно от TTL решения Policy).
 */
data class ContextFreshness(
    val contextCapturedAtMillis: Long,
    val maxAgeMillis: Long = DEFAULT_MAX_AGE_MS,
) {
    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30_000L
    }
}

/**
 * TTL решения AutonomyPolicy.
 */
data class DecisionValidity(
    val decidedAtMillis: Long,
    val ttlMillis: Long = DEFAULT_TTL_MS,
) {
    companion object {
        const val DEFAULT_TTL_MS: Long = 60_000L
    }
}

/**
 * Подтверждение пользователя, привязанное к конкретному действию/fingerprint.
 * Confirmation не повышает risk и не меняет autonomy level.
 */
data class ActionConfirmation(
    val confirmationId: String,
    val boundActionRequestId: String,
    val boundIntegrityFingerprint: String,
    val confirmedAtMillis: Long,
    val expiresAtMillis: Long,
    val consumed: Boolean = false,
)

/**
 * Immutable binding: что именно оценивала Policy (для plan integrity).
 */
data class PolicyDecisionBinding(
    val integrityFingerprint: String,
    val policyAction: ProposedAction,
    val policyRiskAssessment: ActionRiskAssessment,
)

/**
 * Вход Stage 8D. Gate не вызывает RiskClassifier / Policy / Executor.
 */
data class ActionSafetyGateInput(
    val verdict: AutonomyVerdict,
    val riskAssessment: ActionRiskAssessment?,
    val nowMillis: Long,
    val requiredPermissions: Set<String> = emptySet(),
    val grantedPermissions: Set<String> = emptySet(),
    val contextFreshness: ContextFreshness? = null,
    val decisionValidity: DecisionValidity? = null,
    val confirmation: ActionConfirmation? = null,
    val policyBinding: PolicyDecisionBinding? = null,
    /**
     * Действие, предъявленное Gate (может отличаться от policy snapshot —
     * тогда integrity fail).
     * null → берётся из verdict.actionRequest?.proposedAction.
     */
    val presentedAction: ProposedAction? = null,
    /** Явный allowlist ключей аргументов; null → каталог по ActionKind/toolId. */
    val allowedArgumentKeys: Set<String>? = null,
    val forbiddenArgumentKeys: Set<String> = ActionArgumentRules.GLOBAL_FORBIDDEN_KEYS,
)

/**
 * Результат Stage 8D.
 * [verdict] никогда не ослабляется (DENY остаётся DENY; PASS не создаёт ALLOW из DENY).
 */
data class ActionSafetyGateResult(
    val status: ActionSafetyGateStatus,
    val failureReason: ActionSafetyFailureReason? = null,
    val reason: String = "",
    val verdict: AutonomyVerdict,
    val notes: List<String> = emptyList(),
) {
    val passed: Boolean get() = status == ActionSafetyGateStatus.PASS

    init {
        if (status == ActionSafetyGateStatus.PASS) {
            require(failureReason == null) { "PASS must not carry failureReason" }
        }
        if (status == ActionSafetyGateStatus.BLOCK) {
            require(failureReason != null) { "BLOCK requires failureReason" }
        }
        // Never escalate DENY / NO_ACTION to executable ALLOW via gate result verdict.
        if (verdict.type == AutonomyVerdictType.DENY ||
            verdict.type == AutonomyVerdictType.NO_ACTION
        ) {
            require(status == ActionSafetyGateStatus.BLOCK) {
                "DENY/NO_ACTION verdict cannot PASS the safety gate"
            }
        }
    }
}
