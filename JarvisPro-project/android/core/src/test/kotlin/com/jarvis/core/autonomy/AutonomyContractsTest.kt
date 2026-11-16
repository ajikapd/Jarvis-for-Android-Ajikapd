package com.jarvis.core.autonomy

import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8A: contracts only — no ToolExecutor / no real policy.
 * Updated for Stage 8B: ProposedAction.kind (ActionKind).
 */
class AutonomyContractsTest {

    @Test
    fun noOpDecisionYieldsNoActionRequest() {
        val d = decision(ProactiveDecisionType.NO_OP, confidence = 0.99f, relevance = 0.99f)
        val proposed = ProposedAction(
            actionId = "launch_music",
            kind = ActionKind.LAUNCH_APP,
            displaySummary = "Открыть Music",
            toolId = "launcher",
            arguments = mapOf("package" to "com.google.android.apps.youtube.music"),
        )
        assertNull(ActionRequestFactory.fromProactive(d, proposed))
    }

    @Test
    fun confirmationCanBuildActionRequestWithoutExecuting() {
        val d = decision(ProactiveDecisionType.CONFIRMATION_REQUEST, confidence = 0.88f, relevance = 0.80f)
        val proposed = ProposedAction(
            actionId = "launch_music",
            kind = ActionKind.LAUNCH_APP,
            displaySummary = "Открыть Music",
            toolId = "launcher",
            arguments = mapOf("package" to "com.spotify.music"),
        )
        val req = ActionRequestFactory.fromProactive(d, proposed)
        assertNotNull(req)
        assertEquals(DecisionSource.PROACTIVE, req!!.source)
        assertEquals("launch_music", req.proposedAction.actionId)
        assertEquals(0.88f, req.confidence, 0.001f)
    }

    @Test
    fun highConfidenceNeverAllowsInUnimplementedPolicy() {
        val d = decision(ProactiveDecisionType.CONFIRMATION_REQUEST, confidence = 0.95f, relevance = 0.95f)
        val proposed = ProposedAction(
            actionId = "launch_music",
            kind = ActionKind.LAUNCH_APP,
            displaySummary = "Открыть Music",
        )
        val verdict = UnimplementedAutonomyPolicy.evaluate(
            AutonomyPolicyInput(
                decision = d,
                proposedAction = proposed,
                autonomyLevel = AutonomyLevel.ASSISTED,
                nowMillis = 1L,
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, verdict.type)
        assertFalse(verdict.type == AutonomyVerdictType.ALLOW)
        assertEquals(AutonomyReasonCode.NOT_IMPLEMENTED, verdict.reasonCode)
    }

    @Test
    fun autonomyOffDeniesEvenWithMappedAction() {
        val d = decision(ProactiveDecisionType.SUGGESTION, confidence = 0.9f, relevance = 0.7f)
        val proposed = ProposedAction(
            actionId = "volume_up",
            kind = ActionKind.LOCAL_DEVICE_SAFE,
            displaySummary = "Громче",
        )
        val verdict = UnimplementedAutonomyPolicy.evaluate(
            AutonomyPolicyInput(
                decision = d,
                proposedAction = proposed,
                autonomyLevel = AutonomyLevel.OFF,
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, verdict.type)
        assertEquals(AutonomyReasonCode.AUTONOMY_LEVEL_OFF, verdict.reasonCode)
    }

    @Test
    fun safetyGateBlocksPrematureAllow() {
        val req = ActionRequest(
            source = DecisionSource.PROACTIVE,
            proposedAction = ProposedAction(
                actionId = "launch_music",
                kind = ActionKind.LAUNCH_APP,
                displaySummary = "Music",
            ),
            confidence = 0.99f,
            relevance = 0.99f,
        )
        val prematureAllow = AutonomyVerdict(
            type = AutonomyVerdictType.ALLOW,
            reasonCode = AutonomyReasonCode.POLICY_ALLOW,
            reason = "should be blocked by gate",
            actionRequest = req,
        )
        val gated = UnimplementedActionSafetyGate.evaluate(
            ActionSafetyGateInput(
                verdict = prematureAllow,
                riskAssessment = null,
                nowMillis = 1L,
            ),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, gated.status)
        assertEquals(AutonomyVerdictType.DENY, gated.verdict.type)
        assertEquals(AutonomyReasonCode.SAFETY_GATE_DENY, gated.verdict.reasonCode)
    }

    @Test
    fun safetyGateDoesNotWeakenDeny() {
        val deny = AutonomyVerdict(
            type = AutonomyVerdictType.DENY,
            reasonCode = AutonomyReasonCode.POLICY_DENY,
            reason = "blocked",
        )
        val gated = UnimplementedActionSafetyGate.evaluate(
            ActionSafetyGateInput(verdict = deny, riskAssessment = null, nowMillis = 1L),
        )
        assertEquals(ActionSafetyGateStatus.BLOCK, gated.status)
        assertEquals(deny.type, gated.verdict.type)
        assertEquals(deny.reasonCode, gated.verdict.reasonCode)
    }

    @Test
    fun allowVerdictRequiresActionRequest() {
        var threw = false
        try {
            AutonomyVerdict(
                type = AutonomyVerdictType.ALLOW,
                reasonCode = AutonomyReasonCode.POLICY_ALLOW,
                actionRequest = null,
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun noActionForbidsActionRequest() {
        var threw = false
        try {
            AutonomyVerdict(
                type = AutonomyVerdictType.NO_ACTION,
                reasonCode = AutonomyReasonCode.PROACTIVE_NO_OP,
                actionRequest = ActionRequest(
                    source = DecisionSource.PROACTIVE,
                    proposedAction = ProposedAction(
                        actionId = "x",
                        kind = ActionKind.READ_ONLY,
                        displaySummary = "x",
                    ),
                ),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun proposedActionBoundsArguments() {
        var threw = false
        try {
            ProposedAction(
                actionId = "x",
                kind = ActionKind.READ_ONLY,
                displaySummary = "ok",
                arguments = mapOf("secret" to "x".repeat(200)),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unimplementedPolicyNoOp() {
        val d = decision(ProactiveDecisionType.NO_OP, confidence = 0.5f, relevance = 0.5f)
        val v = UnimplementedAutonomyPolicy.evaluate(
            AutonomyPolicyInput(d, null, AutonomyLevel.SAFE_ONLY),
        )
        assertEquals(AutonomyVerdictType.NO_ACTION, v.type)
        assertEquals(AutonomyReasonCode.PROACTIVE_NO_OP, v.reasonCode)
        assertNull(v.actionRequest)
    }

    @Test
    fun kindNoneYieldsNoRequest() {
        val d = decision(ProactiveDecisionType.SUGGESTION, confidence = 0.7f, relevance = 0.7f)
        val proposed = ProposedAction(
            actionId = "noop",
            kind = ActionKind.NONE,
            displaySummary = "none",
        )
        assertNull(ActionRequestFactory.fromProactive(d, proposed))
    }

    private fun decision(
        type: ProactiveDecisionType,
        confidence: Float,
        relevance: Float,
    ): ProactiveDecision = ProactiveDecision(
        type = type,
        behaviorPatternKey = "routine:HEADPHONES→music@22",
        fingerprint = "fp-test",
        reasonCode = if (type == ProactiveDecisionType.NO_OP) {
            ProactiveReasonCode.LOW_RELEVANCE
        } else {
            ProactiveReasonCode.SELECTED
        },
        reason = "test",
        confidence = confidence,
        relevance = relevance,
        createdAtMillis = 1_000L,
    )
}
