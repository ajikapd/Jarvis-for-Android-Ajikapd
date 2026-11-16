package com.jarvis.core.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8D: DefaultActionSafetyGate — fail-closed, no RiskClassifier, no Executor.
 */
class DefaultActionSafetyGateTest {

    private val gate = DefaultActionSafetyGate()
    private val now = 1_000_000L

    private fun assessment(
        action: ProposedAction,
        risk: ActionRiskClass,
        reason: ActionRiskReasonCode = ActionRiskReasonCode.LOCAL_REVERSIBLE,
    ) = ActionRiskAssessment(
        riskClass = risk,
        reasonCode = reason,
        reason = "t",
        factors = listOf("t"),
        actionId = action.actionId,
        kind = action.kind,
    )

    private fun allowVerdict(action: ProposedAction, id: String = "req-1") = AutonomyVerdict(
        type = AutonomyVerdictType.ALLOW,
        reasonCode = AutonomyReasonCode.POLICY_ALLOW,
        reason = "policy allow",
        actionRequest = ActionRequest(
            id = id,
            source = DecisionSource.USER_UTTERANCE,
            proposedAction = action,
            confidence = 0.9f,
            relevance = 0.9f,
            createdAtMillis = now,
        ),
    )

    private fun confirmVerdict(action: ProposedAction, id: String = "req-1") = AutonomyVerdict(
        type = AutonomyVerdictType.REQUIRE_CONFIRMATION,
        reasonCode = AutonomyReasonCode.AUTONOMY_REQUIRES_CONFIRMATION,
        reason = "need confirm",
        actionRequest = ActionRequest(
            id = id,
            source = DecisionSource.PROACTIVE,
            proposedAction = action,
            confidence = 0.9f,
            relevance = 0.9f,
            createdAtMillis = now,
        ),
    )

    private fun validInput(
        verdict: AutonomyVerdict,
        action: ProposedAction,
        risk: ActionRiskClass,
        granted: Set<String> = emptySet(),
        required: Set<String>? = null,
        confirmation: ActionConfirmation? = null,
        presented: ProposedAction? = null,
        contextAt: Long = now,
        decidedAt: Long = now,
        ttl: Long = DecisionValidity.DEFAULT_TTL_MS,
        contextMaxAge: Long = ContextFreshness.DEFAULT_MAX_AGE_MS,
        assessmentOverride: ActionRiskAssessment? = null,
        bindingOverride: PolicyDecisionBinding? = null,
    ): ActionSafetyGateInput {
        val a = assessmentOverride ?: assessment(action, risk)
        val binding = bindingOverride ?: ActionIntegrity.binding(action, a)
        return ActionSafetyGateInput(
            verdict = verdict,
            riskAssessment = a,
            nowMillis = now,
            requiredPermissions = required ?: ActionArgumentRules.requiredPermissionsFor(action),
            grantedPermissions = granted,
            contextFreshness = ContextFreshness(contextAt, contextMaxAge),
            decisionValidity = DecisionValidity(decidedAt, ttl),
            confirmation = confirmation,
            policyBinding = binding,
            presentedAction = presented ?: action,
        )
    }

    // --- positive ---

    @Test
    fun validReadOnlyPasses() {
        val action = ProposedAction("weather", ActionKind.READ_ONLY, "Погода", "weather")
        val r = gate.evaluate(
            validInput(allowVerdict(action), action, ActionRiskClass.NONE),
        )
        assertEquals(ActionSafetyGateStatus.PASS, r.status)
        assertNull(r.failureReason)
        assertEquals(AutonomyVerdictType.ALLOW, r.verdict.type)
    }

    @Test
    fun validLowRiskLocalActionPasses() {
        val action = ProposedAction(
            "flashlight",
            ActionKind.LOCAL_DEVICE_SAFE,
            "Фонарик",
            "flashlight",
            mapOf("action" to "on"),
        )
        val r = gate.evaluate(
            validInput(allowVerdict(action), action, ActionRiskClass.LOW),
        )
        assertEquals(ActionSafetyGateStatus.PASS, r.status)
        assertTrue(r.notes.any { it.contains("gate=pass") })
    }

    @Test
    fun allowDoesNotRequireConfirmation() {
        val action = ProposedAction(
            "flashlight",
            ActionKind.LOCAL_DEVICE_SAFE,
            "Фонарик",
            "flashlight",
            mapOf("action" to "off"),
        )
        val r = gate.evaluate(
            validInput(allowVerdict(action), action, ActionRiskClass.LOW, confirmation = null),
        )
        assertEquals(ActionSafetyGateStatus.PASS, r.status)
    }

    @Test
    fun validActionWithCorrectConfirmationPasses() {
        val action = ProposedAction(
            "music_play",
            ActionKind.MEDIA_CONTROL,
            "Музыка",
            "music",
            mapOf("action" to "play"),
        )
        val a = assessment(action, ActionRiskClass.MEDIUM, ActionRiskReasonCode.USER_VISIBLE_EFFECT)
        val binding = ActionIntegrity.binding(action, a)
        val reqId = "req-media"
        val conf = ActionConfirmation(
            confirmationId = "c1",
            boundActionRequestId = reqId,
            boundIntegrityFingerprint = binding.integrityFingerprint,
            confirmedAtMillis = now - 1_000,
            expiresAtMillis = now + 30_000,
        )
        val r = gate.evaluate(
            validInput(
                confirmVerdict(action, reqId),
                action,
                ActionRiskClass.MEDIUM,
                granted = setOf("capability:MEDIA_PLAYBACK"),
                confirmation = conf,
                assessmentOverride = a,
                bindingOverride = binding,
            ),
        )
        assertEquals(ActionSafetyGateStatus.PASS, r.status)
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, r.verdict.type)
    }

    // --- non-executable ---

    @Test
    fun denyNeverPasses() {
        val deny = AutonomyVerdict(
            type = AutonomyVerdictType.DENY,
            reasonCode = AutonomyReasonCode.POLICY_DENY,
            reason = "blocked",
        )
        val r = gate.evaluate(
            ActionSafetyGateInput(verdict = deny, riskAssessment = null, nowMillis = now),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.DENY_VERDICT, r.failureReason)
        assertEquals(AutonomyVerdictType.DENY, r.verdict.type)
    }

    @Test
    fun noActionNeverBecomesExecutable() {
        val no = AutonomyVerdict(
            type = AutonomyVerdictType.NO_ACTION,
            reasonCode = AutonomyReasonCode.PROACTIVE_NO_OP,
        )
        val r = gate.evaluate(
            ActionSafetyGateInput(verdict = no, riskAssessment = null, nowMillis = now),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.NO_ACTION_VERDICT, r.failureReason)
        assertEquals(AutonomyVerdictType.NO_ACTION, r.verdict.type)
    }

    // --- fail-closed negatives ---

    @Test
    fun missingRiskAssessmentBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val verdict = allowVerdict(action)
        val r = gate.evaluate(
            ActionSafetyGateInput(
                verdict = verdict,
                riskAssessment = null,
                nowMillis = now,
                contextFreshness = ContextFreshness(now),
                decisionValidity = DecisionValidity(now),
                policyBinding = ActionIntegrity.binding(
                    action,
                    assessment(action, ActionRiskClass.LOW),
                ),
                presentedAction = action,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.MISSING_RISK_ASSESSMENT, r.failureReason)
    }

    @Test
    fun riskMismatchBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val wrong = assessment(action, ActionRiskClass.LOW).copy(actionId = "other")
        val r = gate.evaluate(
            validInput(
                allowVerdict(action),
                action,
                ActionRiskClass.LOW,
                assessmentOverride = wrong,
                bindingOverride = ActionIntegrity.binding(action, wrong),
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.RISK_ASSESSMENT_MISMATCH, r.failureReason)
    }

    @Test
    fun invalidArgumentsBlock() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "explode"),
        )
        val r = gate.evaluate(validInput(allowVerdict(action), action, ActionRiskClass.LOW))
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.INVALID_ARGUMENTS, r.failureReason)
    }

    @Test
    fun missingPermissionBlocks() {
        val action = ProposedAction(
            "music_play", ActionKind.MEDIA_CONTROL, "Музыка", "music",
            mapOf("action" to "play"),
        )
        val r = gate.evaluate(
            validInput(
                allowVerdict(action),
                action,
                ActionRiskClass.MEDIUM,
                granted = emptySet(),
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.MISSING_PERMISSION, r.failureReason)
    }

    @Test
    fun staleContextBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val r = gate.evaluate(
            validInput(
                allowVerdict(action),
                action,
                ActionRiskClass.LOW,
                contextAt = now - 120_000,
                contextMaxAge = 30_000,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.STALE_CONTEXT, r.failureReason)
    }

    @Test
    fun expiredDecisionBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val r = gate.evaluate(
            validInput(
                allowVerdict(action),
                action,
                ActionRiskClass.LOW,
                decidedAt = now - 120_000,
                ttl = 60_000,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.EXPIRED_DECISION, r.failureReason)
    }

    @Test
    fun missingConfirmationBlocks() {
        val action = ProposedAction(
            "music_play", ActionKind.MEDIA_CONTROL, "Музыка", "music",
            mapOf("action" to "play"),
        )
        val r = gate.evaluate(
            validInput(
                confirmVerdict(action),
                action,
                ActionRiskClass.MEDIUM,
                granted = setOf("capability:MEDIA_PLAYBACK"),
                confirmation = null,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.MISSING_CONFIRMATION, r.failureReason)
    }

    @Test
    fun expiredConfirmationBlocks() {
        val action = ProposedAction(
            "music_play", ActionKind.MEDIA_CONTROL, "Музыка", "music",
            mapOf("action" to "play"),
        )
        val a = assessment(action, ActionRiskClass.MEDIUM, ActionRiskReasonCode.USER_VISIBLE_EFFECT)
        val binding = ActionIntegrity.binding(action, a)
        val conf = ActionConfirmation(
            confirmationId = "c1",
            boundActionRequestId = "req-1",
            boundIntegrityFingerprint = binding.integrityFingerprint,
            confirmedAtMillis = now - 60_000,
            expiresAtMillis = now - 1_000,
        )
        val r = gate.evaluate(
            validInput(
                confirmVerdict(action),
                action,
                ActionRiskClass.MEDIUM,
                granted = setOf("capability:MEDIA_PLAYBACK"),
                confirmation = conf,
                assessmentOverride = a,
                bindingOverride = binding,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.EXPIRED_CONFIRMATION, r.failureReason)
    }

    @Test
    fun confirmationForAnotherActionBlocks() {
        val action = ProposedAction(
            "music_play", ActionKind.MEDIA_CONTROL, "Музыка", "music",
            mapOf("action" to "play"),
        )
        val a = assessment(action, ActionRiskClass.MEDIUM, ActionRiskReasonCode.USER_VISIBLE_EFFECT)
        val binding = ActionIntegrity.binding(action, a)
        val conf = ActionConfirmation(
            confirmationId = "c1",
            boundActionRequestId = "other-req",
            boundIntegrityFingerprint = binding.integrityFingerprint,
            confirmedAtMillis = now - 1_000,
            expiresAtMillis = now + 30_000,
        )
        val r = gate.evaluate(
            validInput(
                confirmVerdict(action),
                action,
                ActionRiskClass.MEDIUM,
                granted = setOf("capability:MEDIA_PLAYBACK"),
                confirmation = conf,
                assessmentOverride = a,
                bindingOverride = binding,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.CONFIRMATION_ACTION_MISMATCH, r.failureReason)
    }

    @Test
    fun modifiedArgumentsAfterPolicyBlock() {
        val original = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val modified = original.copy(arguments = mapOf("action" to "off"))
        val a = assessment(original, ActionRiskClass.LOW)
        val binding = ActionIntegrity.binding(original, a)
        val r = gate.evaluate(
            validInput(
                allowVerdict(original),
                original,
                ActionRiskClass.LOW,
                presented = modified,
                assessmentOverride = a,
                bindingOverride = binding,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.ARGUMENTS_MODIFIED, r.failureReason)
    }

    @Test
    fun modifiedTargetAfterPolicyBlock() {
        val original = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val modified = original.copy(toolId = "volume")
        val a = assessment(original, ActionRiskClass.LOW)
        val binding = ActionIntegrity.binding(original, a)
        val r = gate.evaluate(
            validInput(
                allowVerdict(original),
                original,
                ActionRiskClass.LOW,
                presented = modified,
                assessmentOverride = assessment(modified, ActionRiskClass.LOW),
                bindingOverride = binding,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertTrue(
            r.failureReason == ActionSafetyFailureReason.TARGET_MODIFIED ||
                r.failureReason == ActionSafetyFailureReason.PLAN_INTEGRITY_FAILURE ||
                r.failureReason == ActionSafetyFailureReason.RISK_ASSESSMENT_MISMATCH,
        )
    }

    @Test
    fun modifiedActionKindAfterPolicyBlock() {
        val original = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val modified = original.copy(kind = ActionKind.COMMUNICATION)
        val a = assessment(original, ActionRiskClass.LOW)
        val binding = ActionIntegrity.binding(original, a)
        val r = gate.evaluate(
            validInput(
                allowVerdict(original),
                original,
                ActionRiskClass.LOW,
                presented = modified,
                assessmentOverride = assessment(modified, ActionRiskClass.LOW),
                bindingOverride = binding,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.ACTION_KIND_MODIFIED, r.failureReason)
    }

    @Test
    fun forbiddenParameterBlocks() {
        val action = ProposedAction(
            "weather", ActionKind.READ_ONLY, "Погода", "weather",
            mapOf("query" to "msk", "token" to "abc"),
        )
        val r = gate.evaluate(validInput(allowVerdict(action), action, ActionRiskClass.NONE))
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.FORBIDDEN_PARAMETER, r.failureReason)
    }

    @Test
    fun unknownParameterBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on", "extra_hack" to "1"),
        )
        val r = gate.evaluate(validInput(allowVerdict(action), action, ActionRiskClass.LOW))
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.UNKNOWN_PARAMETER, r.failureReason)
    }

    @Test
    fun malformedTimestampBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val r = gate.evaluate(
            validInput(allowVerdict(action), action, ActionRiskClass.LOW, decidedAt = 0L),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.MALFORMED_TIMESTAMP, r.failureReason)
    }

    @Test
    fun clockAnomalyBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        // context captured in the far future relative to now
        val r = gate.evaluate(
            validInput(
                allowVerdict(action),
                action,
                ActionRiskClass.LOW,
                contextAt = now + 60_000,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.CLOCK_ANOMALY, r.failureReason)
    }

    @Test
    fun missingContextFreshnessBlocks() {
        val action = ProposedAction(
            "flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight",
            mapOf("action" to "on"),
        )
        val a = assessment(action, ActionRiskClass.LOW)
        val r = gate.evaluate(
            ActionSafetyGateInput(
                verdict = allowVerdict(action),
                riskAssessment = a,
                nowMillis = now,
                contextFreshness = null,
                decisionValidity = DecisionValidity(now),
                policyBinding = ActionIntegrity.binding(action, a),
                presentedAction = action,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.MISSING_CONTEXT_FRESHNESS, r.failureReason)
    }

    @Test
    fun gateDoesNotCallClassifier_usesProvidedAssessment() {
        // Feed LOW assessment for FINANCIAL kind actionId — Gate trusts input, does not reclassify.
        // Integrity uses that assessment; arguments must still validate.
        val action = ProposedAction("weather", ActionKind.READ_ONLY, "Погода", "weather")
        val a = assessment(action, ActionRiskClass.NONE, ActionRiskReasonCode.READ_ONLY)
        val r = gate.evaluate(
            validInput(
                allowVerdict(action),
                action,
                ActionRiskClass.NONE,
                assessmentOverride = a,
            ),
        )
        assertEquals(ActionSafetyGateStatus.PASS, r.status)
        assertNotNull(r)
    }

    @Test
    fun confidenceIrrelevant_forbiddenStillBlocks() {
        val action = ProposedAction(
            "weather", ActionKind.READ_ONLY, "Погода", "weather",
            mapOf("password" to "x"),
        )
        val highConf = allowVerdict(action).let {
            it.copy(
                actionRequest = it.actionRequest!!.copy(confidence = 0.999f),
            )
        }
        val r = gate.evaluate(validInput(highConf, action, ActionRiskClass.NONE))
        assertEquals(ActionSafetyGateStatus.BLOCK, r.status)
        assertEquals(ActionSafetyFailureReason.FORBIDDEN_PARAMETER, r.failureReason)
        assertFalse(r.passed)
    }
}
