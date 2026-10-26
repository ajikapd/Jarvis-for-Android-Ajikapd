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

class ProposedActionMapperTest {

    @Test
    fun mapsFlashlightCapability() {
        val a = ProposedActionMapper.fromCapabilityId("DEVICE_LIGHT", mapOf("action" to "on"))
        assertNotNull(a)
        assertEquals("flashlight", a!!.toolId)
        assertEquals(ActionKind.LOCAL_DEVICE_SAFE, a.kind)
        assertEquals("on", a.arguments["action"])
    }

    @Test
    fun mapsVolumeTool() {
        val a = ProposedActionMapper.fromToolId("volume", actionId = "volume_up")
        assertEquals(ActionKind.LOCAL_DEVICE_SAFE, a!!.kind)
    }

    @Test
    fun unknownToolReturnsNull() {
        assertNull(ProposedActionMapper.fromToolId("not_a_real_tool"))
    }

    @Test
    fun patternKeyHeadphonesSuggestsMusic() {
        val a = ProposedActionMapper.fromBehaviorPatternKey("seq:HEADPHONES→music@22")
        assertNotNull(a)
        assertEquals("music", a!!.toolId)
        assertEquals(ActionKind.MEDIA_CONTROL, a.kind)
    }

    @Test
    fun firstSafeSetIsDeviceLocalOnly() {
        assertTrue("flashlight" in ProposedActionMapper.FIRST_SAFE_TOOL_IDS)
        assertTrue("sms" !in ProposedActionMapper.FIRST_SAFE_TOOL_IDS)
        assertTrue("phone.call" !in ProposedActionMapper.FIRST_SAFE_TOOL_IDS)
    }
}

class AutonomyActionPipelineTest {

    private fun decision(
        type: ProactiveDecisionType = ProactiveDecisionType.SUGGESTION,
        confidence: Float = 0.9f,
    ) = ProactiveDecision(
        type = type,
        reasonCode = if (type == ProactiveDecisionType.NO_OP) {
            ProactiveReasonCode.LOW_RELEVANCE
        } else {
            ProactiveReasonCode.SELECTED
        },
        confidence = confidence,
        relevance = 0.8f,
        createdAtMillis = 1L,
        fingerprint = "fp",
        behaviorPatternKey = "p",
        reason = "test",
    )

    @Test
    fun flashlightReadyToExecuteWhenGatePasses() {
        val pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate())
        val proposed = ProposedActionMapper.fromToolId(
            "flashlight",
            args = mapOf("action" to "on"),
        )!!
        val plan = pipeline.plan(
            decision(),
            proposed,
            AutonomyLevel.SAFE_ONLY,
            nowMillis = 1_000_000L,
        )
        assertEquals(AutonomyPlanStatus.READY_TO_EXECUTE, plan.status)
        assertNotNull(plan.draftToolCall)
        assertEquals("flashlight", plan.draftToolCall!!.toolId)
        assertTrue(plan.gateResult!!.passed)
    }

    @Test
    fun staleContextDeniedByGate() {
        val pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate())
        val proposed = ProposedActionMapper.fromToolId(
            "flashlight",
            args = mapOf("action" to "on"),
        )!!
        val plan = pipeline.plan(
            decision(),
            proposed,
            AutonomyLevel.SAFE_ONLY,
            nowMillis = 1_000_000L,
            contextCapturedAtMillis = 1_000_000L - 120_000L,
        )
        assertEquals(AutonomyPlanStatus.DENIED, plan.status)
        assertEquals(
            ActionSafetyFailureReason.STALE_CONTEXT,
            plan.gateResult!!.failureReason,
        )
    }

    @Test
    fun smsDeniedEvenWithGate() {
        val pipeline = AutonomyActionPipeline(gate = DefaultActionSafetyGate())
        val proposed = ProposedActionMapper.fromToolId("sms")!!
        val plan = pipeline.plan(
            decision(confidence = 0.99f),
            proposed,
            AutonomyLevel.ASSISTED,
            nowMillis = 1_000_000L,
        )
        assertEquals(AutonomyPlanStatus.DENIED, plan.status)
    }
}
