package com.jarvis.core.planner

/**
 * Stage 11: валидация целостности [ActionPlan] (не SafetyGate).
 * Side-effect free, fail-closed.
 */
object ActionPlanValidator {

    fun validate(plan: ActionPlan): ActionPlanValidation {
        val errors = mutableListOf<String>()
        if (plan.steps.isEmpty()) {
            return when (plan.status) {
                ActionPlanStatus.EMPTY, ActionPlanStatus.CANCELLED ->
                    ActionPlanValidation(ok = true, errors = emptyList())
                else -> ActionPlanValidation(
                    ok = false,
                    errors = listOf("non-empty status with no steps"),
                )
            }
        }

        val ids = plan.steps.map { it.stepId }
        if (ids.size != ids.toSet().size) {
            errors += "duplicate stepId"
        }

        val idSet = ids.toSet()
        plan.entryStepId?.let { entry ->
            if (entry !in idSet) errors += "entryStepId missing: $entry"
        }

        for (step in plan.steps) {
            for (dep in step.dependsOn) {
                if (dep !in idSet) errors += "step ${step.stepId} depends on missing $dep"
                if (dep == step.stepId) errors += "step ${step.stepId} self-dependency"
            }
            step.condition.dependsOnStepId?.let { d ->
                if (d !in idSet) errors += "step ${step.stepId} condition depends on missing $d"
            }
            for (b in step.parameterBindings) {
                if (b.fromStepId !in idSet) {
                    errors += "step ${step.stepId} binding from missing ${b.fromStepId}"
                }
                if (b.fromStepId == step.stepId) {
                    errors += "step ${step.stepId} binding self-ref"
                }
            }
            step.branchOnSuccess?.let {
                if (it !in idSet) errors += "step ${step.stepId} branchOnSuccess missing $it"
            }
            step.branchOnFailure?.let {
                if (it !in idSet) errors += "step ${step.stepId} branchOnFailure missing $it"
            }
        }

        if (hasCycle(plan.steps)) {
            errors += "dependency cycle detected"
        }

        return ActionPlanValidation(ok = errors.isEmpty(), errors = errors)
    }

    private fun hasCycle(steps: List<PlanStep>): Boolean {
        val graph = steps.associate { step ->
            val deps = LinkedHashSet<String>()
            deps.addAll(step.dependsOn)
            step.condition.dependsOnStepId?.let { deps += it }
            step.parameterBindings.forEach { deps += it.fromStepId }
            step.stepId to deps
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            for (next in graph[id].orEmpty()) {
                if (dfs(next)) return true
            }
            visiting -= id
            visited += id
            return false
        }

        return graph.keys.any { dfs(it) }
    }
}

data class ActionPlanValidation(
    val ok: Boolean,
    val errors: List<String>,
)
