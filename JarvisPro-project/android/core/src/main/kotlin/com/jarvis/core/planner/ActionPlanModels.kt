package com.jarvis.core.planner

/**
 * Stage 11: цель пользователя для Planner.
 * Не ProposedAction и не ToolExecutionRequest.
 */
data class UserGoal(
    val goalId: String,
    val text: String,
    val createdAtMillis: Long = 0L,
) {
    init {
        require(goalId.isNotBlank()) { "goalId blank" }
        require(text.length <= MAX_TEXT) { "goal text too long" }
    }

    companion object {
        const val MAX_TEXT = 2_000
    }
}

/**
 * Вид шага ActionPlan.
 * Planner только описывает; Execution Loop (Stage 12) исполняет.
 */
enum class PlanStepKind {
    /** Вызов tool/capability через будущий execution loop. */
    TOOL,
    /** Ожидание (duration / future condition). */
    WAIT,
    /** Явное подтверждение пользователя перед продолжением. */
    CONFIRM,
    /** Условное ветвление (branchOnSuccess / branchOnFailure). */
    BRANCH,
    /** Пустой / marker шаг. */
    NOOP,
}

enum class PlanConditionOp {
    ALWAYS,
    PREV_SUCCESS,
    PREV_FAILURE,
    RESULT_EQUALS,
    RESULT_CONTAINS,
}

/**
 * Условие запуска шага (зависимости + результат предыдущего).
 */
data class PlanStepCondition(
    val op: PlanConditionOp = PlanConditionOp.ALWAYS,
    val dependsOnStepId: String? = null,
    val resultKey: String? = null,
    val expectedValue: String? = null,
) {
    init {
        if (op == PlanConditionOp.RESULT_EQUALS || op == PlanConditionOp.RESULT_CONTAINS) {
            require(!dependsOnStepId.isNullOrBlank()) { "result condition needs dependsOnStepId" }
            require(!resultKey.isNullOrBlank()) { "result condition needs resultKey" }
        }
    }
}

data class PlanRetryPolicy(
    val maxAttempts: Int = 1,
    val onlyIfRetryable: Boolean = true,
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS) { "maxAttempts out of bounds" }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Привязка параметра к результату предыдущего шага
 * (Stage 12 подставит фактическое значение).
 */
data class PlanParameterBinding(
    val name: String,
    val fromStepId: String,
    val fromKey: String = "output",
) {
    init {
        require(name.isNotBlank())
        require(fromStepId.isNotBlank())
        require(fromKey.isNotBlank())
        require(name.length <= PlanStep.MAX_PARAM_KEY)
        require(fromKey.length <= PlanStep.MAX_PARAM_KEY)
    }
}

/**
 * Один шаг ActionPlan.
 */
data class PlanStep(
    val stepId: String,
    val kind: PlanStepKind,
    val toolId: String? = null,
    val capabilityId: String? = null,
    /** Bounded literal parameters. */
    val parameters: Map<String, String> = emptyMap(),
    /** Bindings from previous step results. */
    val parameterBindings: List<PlanParameterBinding> = emptyList(),
    /** Explicit dependency step ids (must complete before this). */
    val dependsOn: List<String> = emptyList(),
    val condition: PlanStepCondition = PlanStepCondition(),
    val requiresConfirmation: Boolean = false,
    val retry: PlanRetryPolicy = PlanRetryPolicy(),
    /** For WAIT steps. */
    val waitMs: Long = 0L,
    val branchOnSuccess: String? = null,
    val branchOnFailure: String? = null,
    val cancelOnFailure: Boolean = false,
    val displaySummary: String = "",
    val notes: List<String> = emptyList(),
) {
    init {
        require(stepId.isNotBlank()) { "stepId blank" }
        require(parameters.size <= MAX_PARAMS) { "too many parameters" }
        parameters.forEach { (k, v) ->
            require(k.length <= MAX_PARAM_KEY) { "param key too long" }
            require(v.length <= MAX_PARAM_VALUE) { "param value too long" }
        }
        require(parameterBindings.size <= MAX_PARAMS) { "too many bindings" }
        require(dependsOn.size <= MAX_DEPS) { "too many deps" }
        require(waitMs in 0..MAX_WAIT_MS) { "waitMs out of bounds" }
        require(notes.size <= MAX_NOTES) { "too many notes" }
        when (kind) {
            PlanStepKind.TOOL -> require(!toolId.isNullOrBlank()) { "TOOL step needs toolId" }
            PlanStepKind.WAIT -> require(waitMs > 0L) { "WAIT step needs waitMs > 0" }
            PlanStepKind.CONFIRM -> require(requiresConfirmation || displaySummary.isNotBlank()) {
                "CONFIRM step needs confirmation intent"
            }
            PlanStepKind.BRANCH -> require(
                !branchOnSuccess.isNullOrBlank() || !branchOnFailure.isNullOrBlank(),
            ) { "BRANCH needs at least one branch target" }
            PlanStepKind.NOOP -> Unit
        }
    }

    companion object {
        const val MAX_PARAMS = 16
        const val MAX_PARAM_KEY = 64
        const val MAX_PARAM_VALUE = 512
        const val MAX_DEPS = 8
        const val MAX_WAIT_MS = 3_600_000L
        const val MAX_NOTES = 8
    }
}

enum class ActionPlanStatus {
    /** Нет шагов (цель без actionable tools). */
    EMPTY,
    /** Валидный план готов к Stage 12. */
    READY,
    /** Не прошёл валидацию целостности. */
    INVALID,
    /** Отменён до исполнения. */
    CANCELLED,
}

/**
 * Stage 11: план действий. Planner только создаёт; не исполняет.
 *
 * Поддерживает: последовательность, зависимости, условия, ветвление,
 * ожидание, retry, cancellation, confirmation, refs на результаты шагов,
 * bounded parameters.
 */
data class ActionPlan(
    val planId: String,
    val goalId: String,
    val status: ActionPlanStatus,
    val steps: List<PlanStep>,
    val entryStepId: String? = steps.firstOrNull()?.stepId,
    val cancellationRequested: Boolean = false,
    /**
     * Слоты для результатов шагов (заполняет Stage 12).
     * Ключ = stepId, значение = opaque result key / placeholder.
     */
    val resultSlots: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
) {
    init {
        require(planId.isNotBlank())
        require(goalId.isNotBlank())
        require(steps.size <= MAX_STEPS) { "too many steps" }
        require(notes.size <= MAX_NOTES) { "too many notes" }
        if (cancellationRequested) {
            require(status == ActionPlanStatus.CANCELLED) {
                "cancellationRequested requires CANCELLED status"
            }
        }
    }

    fun step(id: String): PlanStep? = steps.firstOrNull { it.stepId == id }

    companion object {
        const val MAX_STEPS = 16
        const val MAX_NOTES = 16
    }
}

/**
 * Контекст планирования (discovery subset + bounds).
 */
data class PlannerContext(
    val discovery: com.jarvis.core.discovery.DiscoveryResult,
    val isOnline: Boolean? = null,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** Если true — один лучший tool; иначе до maxSteps sequential. */
    val preferSingleStep: Boolean = true,
    val defaultWaitBetweenMs: Long = 0L,
) {
    init {
        require(maxSteps in 1..ActionPlan.MAX_STEPS)
        require(defaultWaitBetweenMs in 0..PlanStep.MAX_WAIT_MS)
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 8
    }
}
