package com.jarvis.app.companion

import android.content.Context
import com.jarvis.core.autonomy.ActionArgumentRules
import com.jarvis.core.autonomy.ActionIntegrity
import com.jarvis.core.autonomy.ActionRiskClassifier
import com.jarvis.core.autonomy.ActionSafetyGate
import com.jarvis.core.autonomy.ActionSafetyGateInput
import com.jarvis.core.autonomy.AutonomyActionPipeline
import com.jarvis.core.autonomy.AutonomyActionPlan
import com.jarvis.core.autonomy.AutonomyAuditLog
import com.jarvis.core.autonomy.AutonomyLevel
import com.jarvis.core.autonomy.AutonomyPolicy
import com.jarvis.core.autonomy.AutonomyPolicyInput
import com.jarvis.core.autonomy.AutonomyVerdict
import com.jarvis.core.autonomy.ContextFreshness
import com.jarvis.core.autonomy.DecisionValidity
import com.jarvis.core.autonomy.DefaultActionSafetyGate
import com.jarvis.core.autonomy.DefaultAutonomyPolicy
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.autonomy.ProposedActionMapper
import com.jarvis.core.autonomy.SafeAutonomousExecutor
import com.jarvis.core.autonomy.SafeAutonomousResult
import com.jarvis.core.behavior.BehaviorCandidate
import com.jarvis.core.behavior.BehaviorFeedback
import com.jarvis.core.behavior.BehaviorLearningService
import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.companion.ContextSnapshot
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.memory.CompanionMemoryService
import com.jarvis.core.proactive.BehaviorFeedbackView
import com.jarvis.core.proactive.DecisionEvaluationResult
import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveRuntime
import com.jarvis.core.proactive.ProactivitySnapshotCodec
import com.jarvis.core.proactive.ProactivityState

/**
 * Stage 7E/7.5 + autonomy adapter.
 * RiskClassifier → AutonomyPolicy → ActionSafetyGate.
 * Proactive tick **не** вызывает ToolExecutor автоматически.
 */
object CompanionProactive {
    @Volatile
    private var runtime: ProactiveRuntime? = null

    @Volatile
    private var autonomyPolicy: AutonomyPolicy = DefaultAutonomyPolicy()

    @Volatile
    private var riskClassifier: ActionRiskClassifier = ActionRiskClassifier()

    @Volatile
    private var safetyGate: ActionSafetyGate = DefaultActionSafetyGate()

    @Volatile
    private var safeExecutor: SafeAutonomousExecutor? = null

    fun autonomyPolicy(): AutonomyPolicy = autonomyPolicy

    fun safetyGate(): ActionSafetyGate = safetyGate

    fun setAutonomyPolicyForTests(policy: AutonomyPolicy) {
        autonomyPolicy = policy
    }

    fun setSafetyGateForTests(gate: ActionSafetyGate) {
        safetyGate = gate
    }

    /**
     * Подключить ToolExecutor для явного safe exec (debug / confirm UI).
     * Не включает auto-exec из [tick].
     */
    fun attachSafeExecutor(toolExecutor: ToolExecutor, live: Boolean = false) {
        // live зарезервирован для будущего Executor; Gate всегда один и тот же.
        @Suppress("UNUSED_VARIABLE")
        val unusedLive = live
        val gate = DefaultActionSafetyGate()
        safetyGate = gate
        safeExecutor = SafeAutonomousExecutor(
            toolExecutor = toolExecutor,
            pipeline = AutonomyActionPipeline(
                policy = autonomyPolicy,
                gate = gate,
                riskClassifier = riskClassifier,
            ),
        )
    }

    fun clearSafeExecutorForTests() {
        safeExecutor = null
        safetyGate = DefaultActionSafetyGate()
    }

    /**
     * RiskClassifier → AutonomyPolicy → ActionSafetyGate.
     * Возвращает hardened verdict (PASS сохраняет Policy verdict; BLOCK ужесточает).
     */
    fun evaluateAutonomy(
        decision: ProactiveDecision,
        proposed: ProposedAction?,
        level: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
        allowlist: Set<String> = emptySet(),
        blocklist: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
        grantedPermissions: Set<String> = emptySet(),
    ): AutonomyVerdict {
        val assessment = proposed?.let { riskClassifier.classify(it) }
        val policyVerdict = autonomyPolicy.evaluate(
            AutonomyPolicyInput(
                decision = decision,
                proposedAction = proposed,
                autonomyLevel = level,
                riskAssessment = assessment,
                userAllowlistActionIds = allowlist,
                userBlocklistActionIds = blocklist,
                nowMillis = nowMillis,
            ),
        )
        AutonomyAuditLog.record(policyVerdict, stage = "policy")
        val binding = if (proposed != null && assessment != null) {
            ActionIntegrity.binding(proposed, assessment)
        } else {
            null
        }
        val required = proposed?.let { ActionArgumentRules.requiredPermissionsFor(it) }.orEmpty()
        val gateResult = safetyGate.evaluate(
            ActionSafetyGateInput(
                verdict = policyVerdict,
                riskAssessment = assessment,
                nowMillis = nowMillis,
                requiredPermissions = required,
                grantedPermissions = grantedPermissions,
                contextFreshness = ContextFreshness(contextCapturedAtMillis = nowMillis),
                decisionValidity = DecisionValidity(decidedAtMillis = nowMillis),
                policyBinding = binding,
                presentedAction = proposed,
            ),
        )
        AutonomyAuditLog.record(gateResult.verdict, stage = "gate")
        return gateResult.verdict
    }

    fun planAutonomy(
        decision: ProactiveDecision,
        proposed: ProposedAction?,
        level: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
        grantedPermissions: Set<String> = emptySet(),
    ): AutonomyActionPlan = AutonomyActionPipeline(
        policy = autonomyPolicy,
        gate = safetyGate,
        riskClassifier = riskClassifier,
    ).plan(
        decision = decision,
        proposed = proposed,
        level = level,
        grantedPermissions = grantedPermissions,
    )

    /**
     * Stage 8F: явный safe exec (flashlight/volume/brightness).
     * Требует [attachSafeExecutor] с live=true. Не вызывается из [tick].
     */
    fun trySafeExecute(
        decision: ProactiveDecision,
        proposed: ProposedAction,
        level: AutonomyLevel = AutonomyLevel.SAFE_ONLY,
    ): SafeAutonomousResult {
        val exec = safeExecutor
            ?: return SafeAutonomousResult(
                executed = false,
                plan = null,
                result = null,
                reason = "SafeAutonomousExecutor not attached",
            )
        return exec.tryExecute(decision, proposed, level)
    }

    /** Удобный маппер patternKey → ProposedAction (8E). */
    fun proposeFromPattern(patternKey: String): ProposedAction? =
        ProposedActionMapper.fromBehaviorPatternKey(patternKey)

    fun get(context: Context): ProactiveRuntime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: run {
                val appCtx = context.applicationContext
                val rt = ProactiveRuntime()
                val raw = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STATE, null)
                rt.restore(ProactivitySnapshotCodec.decode(raw.orEmpty()))
                runtime = rt
                rt
            }
        }
    }

    /**
     * Один тик в существующем Companion render/observation flow.
     * BehaviorLearning уже должен был обновить store.
     *
     * [bypassCoalesce] / [force] — только сброс runtime minInterval (diagnostics).
     * Не обходит DISABLE / cooldown / budget / alreadySatisfied / relevance / duplicate.
     */
    fun tick(
        context: Context,
        behavior: BehaviorLearningService,
        snapshot: ContextSnapshot = PhoneContextBus.snapshot(),
        force: Boolean = false,
        bypassCoalesce: Boolean = force,
    ): DecisionEvaluationResult {
        val rt = get(context)
        val now = snapshot.takenAtMillis
        val candidates: List<BehaviorCandidate> = behavior.store().activeCandidates(now)
        val feedback = BehaviorFeedbackView.from(behavior.store())
        val result = rt.evaluate(
            snapshot = snapshot,
            candidates = candidates,
            feedback = feedback,
            nowMillis = now,
            bypassCoalesce = bypassCoalesce,
        )
        if (com.jarvis.app.BuildConfig.DEBUG) {
            android.util.Log.i(
                "JarvisProactive",
                "tick type=${result.decision.type} reason=${result.decision.reasonCode} " +
                    "key=${result.decision.behaviorPatternKey} " +
                    "hp=${snapshot.context.headphonesConnected} " +
                    "app=${snapshot.context.currentApp} " +
                    "cands=${candidates.size} bypass=$bypassCoalesce",
            )
        }
        persist(context, rt.state())
        return result
    }

    /** Production UI: только non-NO_OP. */
    fun visibleDecision(context: Context): ProactiveDecision? {
        val d = get(context).lastVisibleDecision() ?: return null
        return if (d.type == ProactiveDecisionType.NO_OP) null else d
    }

    /** Debug/diagnostics only — включает NO_OP reason. */
    fun lastEvaluatedDecision(context: Context): ProactiveDecision? =
        get(context).lastEvaluatedDecision()

    /**
     * Feedback только через BehaviorLearningService — затем proactive state.
     * ACCEPT не выполняет Android action.
     */
    fun applyFeedback(
        context: Context,
        behavior: BehaviorLearningService,
        feedback: BehaviorFeedback,
        memory: CompanionMemoryService? = null,
        patternKey: String? = null,
    ) {
        val rt = get(context)
        val key = patternKey
            ?: rt.lastVisibleDecision()?.behaviorPatternKey
            ?: return
        behavior.feedback(key, feedback, memory = memory)
        CompanionBehavior.persistFeedback(context, behavior)
        rt.afterFeedback(key, feedback)
        persist(context, rt.state())
    }

    fun persist(context: Context, state: ProactivityState = get(context).state()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, ProactivitySnapshotCodec.encode(state))
            .apply()
    }

    /** Tests / process reset. */
    fun resetForTests() {
        synchronized(this) {
            runtime = null
        }
    }

    private const val PREFS = "jarvis_proactivity_v1"
    private const val KEY_STATE = "proactivity_state_v1"
}
