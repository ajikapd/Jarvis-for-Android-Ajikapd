package com.jarvis.core.aioutput

/**
 * V2-05: unified Structured AI Output contract.
 * Describes model intent only — never permission, autonomy, or execution success.
 */
enum class StructuredAiResponseType {
    ANSWER,
    CLARIFICATION,
    TOOL_REQUEST,
    PLAN,
    NO_ACTION,
    CONFIRMATION_REQUIRED,
}

enum class ClarificationReason {
    AMBIGUOUS_TARGET,
    MISSING_SLOT,
    MULTIPLE_CANDIDATES,
    UNSURE_INTENT,
    OTHER,
}

enum class StructuredParseStatus {
    SUCCESS,
    FAIL,
}

enum class StructuredValidationStatus {
    SUCCESS,
    FAIL,
    SKIPPED,
}

/**
 * Tasks that **must** produce structured output (CHAT may stay free text).
 */
enum class StructuredAiTask {
    SEMANTIC_INTERPRETATION,
    TOOL_REQUEST,
    PLAN,
    /** Optional wrapper; free text ANSWER fallback allowed. */
    CHAT,
}

object StructuredAiLimits {
    const val SCHEMA_VERSION = "1"
    const val MAX_JSON_CHARS = 4_096
    const val MAX_TEXT_CHARS = 1_200
    const val MAX_QUESTION_CHARS = 400
    const val MAX_ARGS = 12
    const val MAX_ARG_KEY = 48
    const val MAX_ARG_VALUE = 96
    const val MAX_PLAN_STEPS = 16
    const val MAX_NESTING_DEPTH = 4
    const val MAX_REPAIR_ATTEMPTS = 1
    const val MAX_GOAL_CHARS = 160
    const val MAX_TOOL_ID = 64

    /** Security-sensitive keys that LLM must never set as tool args. */
    val FORBIDDEN_ARG_KEYS: Set<String> = setOf(
        "allow", "permission", "autonomy", "confirmed", "confirmation",
        "bypass", "policy", "risk", "token", "api_key", "password", "secret",
        "success", "executed", "override",
    )
}

/**
 * Immutable envelope after successful parse + schema validation.
 * Semantic validation may still fail (unknown tool, etc.).
 */
data class StructuredAiEnvelope(
    val schemaVersion: String,
    val responseType: StructuredAiResponseType,
    val requestId: String? = null,
    val confidence: Float? = null,
    val text: String? = null,
    val question: String? = null,
    val clarificationReason: ClarificationReason? = null,
    val toolId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val goal: String? = null,
    val intent: String? = null,
    val suggestedRoute: String? = null,
    val ambiguity: String? = null,
    val planSteps: List<StructuredPlanStepDraft> = emptyList(),
    val confirmationSummary: String? = null,
    val noActionReason: String? = null,
) {
    init {
        require(schemaVersion.isNotBlank())
        require(arguments.size <= StructuredAiLimits.MAX_ARGS)
        require(planSteps.size <= StructuredAiLimits.MAX_PLAN_STEPS)
    }
}

/**
 * Draft plan step from LLM — converted to [com.jarvis.core.planner.PlanStep] after validation.
 */
data class StructuredPlanStepDraft(
    val stepId: String,
    val kind: String,
    val toolId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val displaySummary: String = "",
    val waitMs: Long = 0L,
)

data class StructuredAiDiagnostics(
    val structured: Boolean,
    val schemaVersion: String?,
    val responseType: String?,
    val parse: StructuredParseStatus,
    val validation: StructuredValidationStatus,
    val repairAttempted: Boolean = false,
    val repairResult: String? = null,
    val validationReason: String? = null,
    val parseMs: Long = 0L,
) {
    fun logLine(): String =
        "structured=$structured schema=${schemaVersion ?: "-"} type=${responseType ?: "-"} " +
            "parse=$parse validation=$validation repair=$repairAttempted " +
            "repairResult=${repairResult ?: "-"} reason=${validationReason ?: "-"} parseMs=$parseMs"
}

sealed class StructuredAiProcessResult {
    data class Valid(
        val envelope: StructuredAiEnvelope,
        val diagnostics: StructuredAiDiagnostics,
    ) : StructuredAiProcessResult()

    data class Invalid(
        val diagnostics: StructuredAiDiagnostics,
        val rawPreview: String,
    ) : StructuredAiProcessResult()
}
