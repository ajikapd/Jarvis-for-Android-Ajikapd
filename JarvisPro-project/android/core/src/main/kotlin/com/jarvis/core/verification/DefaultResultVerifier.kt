package com.jarvis.core.verification

/**
 * Stage 13: Result Verification.
 * Сравнивает tool result + observed state. Не Executor / не Policy.
 */
fun interface ResultVerifier {
    fun verify(request: VerificationRequest): VerificationResult
}

/**
 * Детерминированный verifier.
 *
 * Правила:
 * - tool failure + irreversible → IRREVERSIBLE
 * - tool failure + retryable status → RETRYABLE
 * - tool failure → FAILURE
 * - нет expected effects → SUCCESS если tool ok, иначе UNKNOWN
 * - есть required expected, нет observed → UNKNOWN
 * - все required совпали, без mismatch → SUCCESS
 * - часть required / optional mismatch → PARTIAL_SUCCESS
 * - ни одного match при required → FAILURE
 */
class DefaultResultVerifier : ResultVerifier {

    override fun verify(request: VerificationRequest): VerificationResult {
        val toolOk = isToolSuccess(request.toolResultStatus)
        val toolFailed = isToolFailure(request.toolResultStatus)

        if (toolFailed) {
            if (request.irreversibleAction) {
                return VerificationResult(
                    status = VerificationStatus.IRREVERSIBLE,
                    notes = listOf(
                        "tool_failed_irreversible",
                        request.toolResultMessage.orEmpty().take(80),
                    ),
                )
            }
            if (isRetryableStatus(request.toolResultStatus)) {
                return VerificationResult(
                    status = VerificationStatus.RETRYABLE,
                    notes = listOf(
                        "tool_failed_retryable",
                        "status=${request.toolResultStatus}",
                    ),
                )
            }
            return VerificationResult(
                status = VerificationStatus.FAILURE,
                notes = listOf(
                    "tool_failed",
                    "status=${request.toolResultStatus}",
                    request.toolResultMessage.orEmpty().take(80),
                ),
            )
        }

        val expected = request.expectedEffects
        if (expected.isEmpty()) {
            return if (toolOk || request.toolResultStatus.isNullOrBlank()) {
                VerificationResult(
                    status = VerificationStatus.SUCCESS,
                    notes = listOf("no_expected_effects", "tool_ok=$toolOk"),
                )
            } else {
                VerificationResult(
                    status = VerificationStatus.UNKNOWN,
                    notes = listOf("no_expected_effects", "tool_status=${request.toolResultStatus}"),
                )
            }
        }

        val observed = request.observed
        val required = expected.filter { it.required }
        if (observed == null) {
            return if (required.isEmpty() && toolOk) {
                VerificationResult(
                    status = VerificationStatus.SUCCESS,
                    notes = listOf("optional_effects_only", "no_observed"),
                )
            } else {
                VerificationResult(
                    status = VerificationStatus.UNKNOWN,
                    missing = required.map { it.key },
                    notes = listOf("no_observed_state", "required=${required.size}"),
                )
            }
        }

        val matched = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for (effect in expected) {
            val actual = observed.get(effect.key) ?: request.toolResultData[effect.key]
            when {
                actual == null -> {
                    if (effect.required) missing += effect.key
                }
                actual.equals(effect.expectedValue, ignoreCase = true) ->
                    matched += effect.key
                else -> {
                    if (effect.required || true) {
                        mismatched += "${effect.key}:expected=${effect.expectedValue}:actual=$actual"
                    }
                }
            }
        }

        val requiredKeys = required.map { it.key }.toSet()
        val requiredMatched = matched.count { it in requiredKeys }
        val requiredTotal = requiredKeys.size

        val status = when {
            missing.isEmpty() &&
                mismatched.none { line -> requiredKeys.any { line.startsWith("$it:") } } &&
                requiredMatched == requiredTotal &&
                matched.size == expected.size ->
                VerificationStatus.SUCCESS
            missing.isEmpty() && requiredMatched == requiredTotal && mismatched.isEmpty() ->
                VerificationStatus.SUCCESS
            requiredMatched > 0 && (missing.isNotEmpty() || mismatched.isNotEmpty() ||
                requiredMatched < requiredTotal) ->
                VerificationStatus.PARTIAL_SUCCESS
            requiredTotal == 0 && matched.isNotEmpty() && mismatched.isEmpty() && missing.isEmpty() ->
                VerificationStatus.SUCCESS
            requiredTotal == 0 && matched.isNotEmpty() ->
                VerificationStatus.PARTIAL_SUCCESS
            missing.isNotEmpty() || mismatched.isNotEmpty() ->
                VerificationStatus.FAILURE
            else ->
                VerificationStatus.UNKNOWN
        }

        return VerificationResult(
            status = status,
            matched = matched,
            mismatched = mismatched,
            missing = missing,
            notes = listOf(
                "tool_ok=$toolOk",
                "observed_source=${observed.source}",
                "facts=${observed.facts.size}",
            ),
        )
    }

    private fun isToolSuccess(status: String?): Boolean {
        if (status.isNullOrBlank()) return false
        val s = status.uppercase()
        return s == "SUCCESS" || s == "DRY_RUN_READY" || s == "OK"
    }

    private fun isToolFailure(status: String?): Boolean {
        if (status.isNullOrBlank()) return false
        val s = status.uppercase()
        return s in setOf("FAILED", "FAILURE", "DENIED", "ERROR", "BLOCKED")
    }

    private fun isRetryableStatus(status: String?): Boolean {
        if (status.isNullOrBlank()) return false
        val s = status.uppercase()
        return s in setOf("FAILED", "FAILURE", "UNAVAILABLE", "OFFLINE", "TIMEOUT")
    }
}
