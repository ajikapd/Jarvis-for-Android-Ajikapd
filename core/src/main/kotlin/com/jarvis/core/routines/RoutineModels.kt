package com.jarvis.core.routines

import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.planner.ActionPlanValidator
import com.jarvis.core.planner.DeterministicAIPlanner
import com.jarvis.core.planner.PlanStepKind
import com.jarvis.core.planner.PlannerContext
import com.jarvis.core.planner.UserGoal
import com.jarvis.core.discovery.DiscoveryResult
import java.util.UUID

/**
 * Stage 19: сохранённая рутина (после preview + user approval).
 */
enum class RoutineStatus {
    DRAFT,
    PREVIEW,
    APPROVED,
    SAVED,
    DISABLED,
    REJECTED,
}

data class SavedRoutine(
    val id: String,
    val title: String,
    val naturalLanguage: String,
    val plan: ActionPlan,
    val status: RoutineStatus,
    val createdAtMillis: Long,
    val approvedAtMillis: Long? = null,
    val sourceBehaviorKey: String? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank() && title.length <= 120)
        require(naturalLanguage.length <= 2_000)
    }
}

data class RoutineDraft(
    val draftId: String,
    val naturalLanguage: String,
    val plan: ActionPlan,
    val validationOk: Boolean,
    val validationErrors: List<String> = emptyList(),
    val previewSummary: String,
)

/**
 * Stage 19: NL → ActionPlan → Validate → Preview → Approval → SavedRoutine.
 */
class RoutineGenerator(
    private val planner: DeterministicAIPlanner = DeterministicAIPlanner(),
) {
    fun generate(
        naturalLanguage: String,
        discovery: DiscoveryResult,
        nowMillis: Long = System.currentTimeMillis(),
    ): RoutineDraft {
        val goal = UserGoal(
            goalId = "routine-${UUID.randomUUID()}",
            text = naturalLanguage.take(UserGoal.MAX_TEXT),
            createdAtMillis = nowMillis,
        )
        val plan = planner.plan(
            goal,
            PlannerContext(discovery = discovery, preferSingleStep = false, maxSteps = 6),
        )
        val validation = ActionPlanValidator.validate(plan)
        val summary = buildPreview(plan)
        return RoutineDraft(
            draftId = "draft-${UUID.randomUUID()}",
            naturalLanguage = naturalLanguage,
            plan = plan,
            validationOk = validation.ok && plan.status == ActionPlanStatus.READY,
            validationErrors = validation.errors,
            previewSummary = summary,
        )
    }

    fun approve(draft: RoutineDraft, title: String? = null, nowMillis: Long = System.currentTimeMillis()): SavedRoutine? {
        if (!draft.validationOk) return null
        return SavedRoutine(
            id = "routine-${UUID.randomUUID()}",
            title = (title ?: draft.naturalLanguage.take(60)).ifBlank { "Routine" },
            naturalLanguage = draft.naturalLanguage,
            plan = draft.plan,
            status = RoutineStatus.SAVED,
            createdAtMillis = nowMillis,
            approvedAtMillis = nowMillis,
            notes = listOf("approved_from=${draft.draftId}"),
        )
    }

    fun reject(draft: RoutineDraft, nowMillis: Long = System.currentTimeMillis()): SavedRoutine =
        SavedRoutine(
            id = "routine-${UUID.randomUUID()}",
            title = draft.naturalLanguage.take(60).ifBlank { "Rejected" },
            naturalLanguage = draft.naturalLanguage,
            plan = draft.plan,
            status = RoutineStatus.REJECTED,
            createdAtMillis = nowMillis,
            notes = listOf("rejected_from=${draft.draftId}"),
        )

    private fun buildPreview(plan: ActionPlan): String {
        if (plan.steps.isEmpty()) return "Пустой план"
        return plan.steps.joinToString(" → ") { step ->
            when (step.kind) {
                PlanStepKind.TOOL -> step.toolId ?: step.displaySummary
                PlanStepKind.CONFIRM -> "CONFIRM"
                PlanStepKind.WAIT -> "WAIT(${step.waitMs}ms)"
                PlanStepKind.BRANCH -> "BRANCH"
                PlanStepKind.NOOP -> "NOOP"
            }
        }.take(240)
    }
}

/** In-memory store for Stage 19 (persistent storage — later). */
class RoutineStore {
    private val byId = LinkedHashMap<String, SavedRoutine>()

    fun save(routine: SavedRoutine) {
        require(routine.status == RoutineStatus.SAVED || routine.status == RoutineStatus.DISABLED)
        byId[routine.id] = routine
    }

    fun find(id: String): SavedRoutine? = byId[id]
    fun all(): List<SavedRoutine> = byId.values.toList()
    fun enabled(): List<SavedRoutine> = byId.values.filter { it.status == RoutineStatus.SAVED }
    fun size(): Int = byId.size

    fun disable(id: String): Boolean {
        val r = byId[id] ?: return false
        byId[id] = r.copy(status = RoutineStatus.DISABLED)
        return true
    }
}
