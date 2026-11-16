package com.jarvis.core.autonomy

import com.jarvis.core.proactive.ProactiveDecision
import com.jarvis.core.proactive.ProactiveDecisionType
import com.jarvis.core.proactive.ProactiveReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8C: DefaultAutonomyPolicy — risk assessment is an **input**,
 * classifier is not invoked inside policy.
 */
class DefaultAutonomyPolicyTest {

    private val policy = DefaultAutonomyPolicy()

    private fun decision(
        type: ProactiveDecisionType = ProactiveDecisionType.SUGGESTION,
        confidence: Float = 0.9f,
        relevance: Float = 0.8f,
    ) = ProactiveDecision(
        type = type,
        reasonCode = if (type == ProactiveDecisionType.NO_OP) {
            ProactiveReasonCode.LOW_RELEVANCE
        } else {
            ProactiveReasonCode.SELECTED
        },
        confidence = confidence,
        relevance = relevance,
        createdAtMillis = 1L,
        fingerprint = "fp",
        behaviorPatternKey = "p",
        reason = "test",
    )

    private fun assessment(
        actionId: String,
        risk: ActionRiskClass,
        kind: ActionKind,
        reasonCode: ActionRiskReasonCode = ActionRiskReasonCode.LOCAL_REVERSIBLE,
    ) = ActionRiskAssessment(
        riskClass = risk,
        reasonCode = reasonCode,
        reason = "test",
        factors = listOf("test"),
        actionId = actionId,
        kind = kind,
    )

    private fun input(
        action: ProposedAction,
        level: AutonomyLevel,
        risk: ActionRiskClass,
        type: ProactiveDecisionType = ProactiveDecisionType.SUGGESTION,
        confidence: Float = 0.95f,
        allow: Set<String> = emptySet(),
        block: Set<String> = emptySet(),
        source: DecisionSource = DecisionSource.PROACTIVE,
        assessmentOverride: ActionRiskAssessment? = null,
    ): AutonomyPolicyInput {
        val kind = when (risk) {
            ActionRiskClass.NONE -> ActionKind.READ_ONLY
            ActionRiskClass.LOW -> ActionKind.LOCAL_DEVICE_SAFE
            ActionRiskClass.MEDIUM -> ActionKind.MEDIA_CONTROL
            ActionRiskClass.HIGH -> ActionKind.COMMUNICATION
            ActionRiskClass.CRITICAL -> ActionKind.FINANCIAL
        }
        return AutonomyPolicyInput(
            decision = decision(type, confidence, 0.9f),
            proposedAction = action,
            autonomyLevel = level,
            riskAssessment = assessmentOverride ?: assessment(
                actionId = action.actionId,
                risk = risk,
                kind = action.kind.takeIf { it != ActionKind.UNKNOWN } ?: kind,
            ),
            source = source,
            userAllowlistActionIds = allow,
            userBlocklistActionIds = block,
            nowMillis = 1L,
        )
    }

    // --- matrix: SAFE_ONLY ---

    @Test
    fun safeOnlyAllowsNoneAndLow() {
        assertEquals(
            AutonomyVerdictType.ALLOW,
            policy.evaluate(
                input(
                    ProposedAction("weather", ActionKind.READ_ONLY, "Погода"),
                    AutonomyLevel.SAFE_ONLY,
                    ActionRiskClass.NONE,
                ),
            ).type,
        )
        assertEquals(
            AutonomyVerdictType.ALLOW,
            policy.evaluate(
                input(
                    ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight"),
                    AutonomyLevel.SAFE_ONLY,
                    ActionRiskClass.LOW,
                ),
            ).type,
        )
    }

    @Test
    fun safeOnlyConfirmsMedium() {
        val v = policy.evaluate(
            input(
                ProposedAction("music_play", ActionKind.MEDIA_CONTROL, "Музыка", "music"),
                AutonomyLevel.SAFE_ONLY,
                ActionRiskClass.MEDIUM,
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, v.type)
    }

    @Test
    fun safeOnlyDeniesHigh() {
        val v = policy.evaluate(
            input(
                ProposedAction("sms_send", ActionKind.COMMUNICATION, "SMS", "sms"),
                AutonomyLevel.SAFE_ONLY,
                ActionRiskClass.HIGH,
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
        assertEquals(AutonomyReasonCode.RISK_CLASS_TOO_HIGH, v.reasonCode)
    }

    // --- confidence never upgrades ---

    @Test
    fun highConfidenceNeverAllowsHighRisk() {
        val v = policy.evaluate(
            input(
                ProposedAction("sms_send", ActionKind.COMMUNICATION, "SMS", "sms"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.HIGH,
                confidence = 0.99f,
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
        assertFalse(v.type == AutonomyVerdictType.ALLOW)
    }

    // --- CONFIRM_ALL ---

    @Test
    fun confirmAllRequiresConfirmationForLow() {
        val v = policy.evaluate(
            input(
                ProposedAction("volume_up", ActionKind.LOCAL_DEVICE_SAFE, "Громче", "volume"),
                AutonomyLevel.CONFIRM_ALL,
                ActionRiskClass.LOW,
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, v.type)
    }

    @Test
    fun confirmAllAllowsReadOnlyNone() {
        val v = policy.evaluate(
            input(
                ProposedAction("weather_query", ActionKind.READ_ONLY, "Погода", "weather"),
                AutonomyLevel.CONFIRM_ALL,
                ActionRiskClass.NONE,
            ),
        )
        assertEquals(AutonomyVerdictType.ALLOW, v.type)
    }

    // --- ASSISTED ---

    @Test
    fun assistedConfirmsMedium() {
        val v = policy.evaluate(
            input(
                ProposedAction("music_play", ActionKind.MEDIA_CONTROL, "Музыка", "music"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.MEDIUM,
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, v.type)
    }

    @Test
    fun assistedAllowsLow() {
        val v = policy.evaluate(
            input(
                ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.LOW,
            ),
        )
        assertEquals(AutonomyVerdictType.ALLOW, v.type)
        assertEquals(AutonomyReasonCode.POLICY_ALLOW, v.reasonCode)
    }

    // --- allow / block ---

    @Test
    fun blocklistDeniesEvenSafe() {
        val v = policy.evaluate(
            input(
                ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", "flashlight"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.LOW,
                block = setOf("flashlight"),
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
        assertEquals(AutonomyReasonCode.USER_BLOCKLISTED, v.reasonCode)
    }

    @Test
    fun highAllowlistedStillRequiresConfirmNotAllow() {
        val v = policy.evaluate(
            input(
                ProposedAction("sms_send", ActionKind.COMMUNICATION, "SMS", "sms"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.HIGH,
                allow = setOf("sms_send"),
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, v.type)
        assertTrue(v.notes.any { it.contains("conf_irrelevant") || it.contains("allowlisted=true") })
    }

    @Test
    fun criticalAllowlistedGetsConfirmOnly() {
        val v = policy.evaluate(
            input(
                ProposedAction("payment", ActionKind.FINANCIAL, "Оплата"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.CRITICAL,
                allow = setOf("payment"),
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, v.type)
    }

    @Test
    fun criticalWithoutAllowlistDenied() {
        val v = policy.evaluate(
            input(
                ProposedAction("payment", ActionKind.FINANCIAL, "Оплата"),
                AutonomyLevel.ASSISTED,
                ActionRiskClass.CRITICAL,
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
    }

    // --- OFF / NO_OP / fail-closed ---

    @Test
    fun autonomyOffDenies() {
        val v = policy.evaluate(
            input(
                ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик"),
                AutonomyLevel.OFF,
                ActionRiskClass.LOW,
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
        assertEquals(AutonomyReasonCode.AUTONOMY_LEVEL_OFF, v.reasonCode)
    }

    @Test
    fun noOpYieldsNoAction() {
        val v = policy.evaluate(
            AutonomyPolicyInput(
                decision = decision(ProactiveDecisionType.NO_OP, 0.99f, 0.99f),
                proposedAction = ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "x"),
                autonomyLevel = AutonomyLevel.ASSISTED,
                riskAssessment = assessment("flashlight", ActionRiskClass.LOW, ActionKind.LOCAL_DEVICE_SAFE),
            ),
        )
        assertEquals(AutonomyVerdictType.NO_ACTION, v.type)
        assertNull(v.actionRequest)
    }

    @Test
    fun missingAssessmentIsFailClosedDeny() {
        val v = policy.evaluate(
            AutonomyPolicyInput(
                decision = decision(),
                proposedAction = ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "x"),
                autonomyLevel = AutonomyLevel.SAFE_ONLY,
                riskAssessment = null,
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
        assertEquals(AutonomyReasonCode.MISSING_RISK_ASSESSMENT, v.reasonCode)
    }

    @Test
    fun mismatchedAssessmentIsFailClosedDeny() {
        val v = policy.evaluate(
            input(
                ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "x"),
                AutonomyLevel.SAFE_ONLY,
                ActionRiskClass.LOW,
                assessmentOverride = assessment(
                    actionId = "other_action",
                    risk = ActionRiskClass.LOW,
                    kind = ActionKind.LOCAL_DEVICE_SAFE,
                ),
            ),
        )
        assertEquals(AutonomyVerdictType.DENY, v.type)
        assertEquals(AutonomyReasonCode.RISK_ASSESSMENT_MISMATCH, v.reasonCode)
    }

    // --- DecisionSource ---

    @Test
    fun systemSourceNeverSilentAllowsLow() {
        val v = policy.evaluate(
            input(
                ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик"),
                AutonomyLevel.SAFE_ONLY,
                ActionRiskClass.LOW,
                source = DecisionSource.SYSTEM,
            ),
        )
        assertEquals(AutonomyVerdictType.REQUIRE_CONFIRMATION, v.type)
        assertTrue(v.notes.any { it.contains("system_no_silent_allow") })
    }

    @Test
    fun systemSourceAllowsReadOnlyNone() {
        val v = policy.evaluate(
            input(
                ProposedAction("weather", ActionKind.READ_ONLY, "Погода"),
                AutonomyLevel.SAFE_ONLY,
                ActionRiskClass.NONE,
                source = DecisionSource.SYSTEM,
            ),
        )
        assertEquals(AutonomyVerdictType.ALLOW, v.type)
    }

    @Test
    fun userUtteranceAllowsLowOnSafeOnly() {
        val v = policy.evaluate(
            input(
                ProposedAction("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик"),
                AutonomyLevel.SAFE_ONLY,
                ActionRiskClass.LOW,
                source = DecisionSource.USER_UTTERANCE,
            ),
        )
        assertEquals(AutonomyVerdictType.ALLOW, v.type)
    }

    @Test
    fun policyDoesNotCallClassifier_explicitAssessmentControlsVerdict() {
        // Classifier would mark FINANCIAL as CRITICAL; we feed LOW assessment → ALLOW.
        // Proves policy trusts input assessment and does not re-classify.
        val v = policy.evaluate(
            AutonomyPolicyInput(
                decision = decision(),
                proposedAction = ProposedAction("payment", ActionKind.FINANCIAL, "Оплата"),
                autonomyLevel = AutonomyLevel.SAFE_ONLY,
                riskAssessment = assessment(
                    actionId = "payment",
                    risk = ActionRiskClass.LOW,
                    kind = ActionKind.LOCAL_DEVICE_SAFE,
                ),
            ),
        )
        assertEquals(AutonomyVerdictType.ALLOW, v.type)
    }
}
