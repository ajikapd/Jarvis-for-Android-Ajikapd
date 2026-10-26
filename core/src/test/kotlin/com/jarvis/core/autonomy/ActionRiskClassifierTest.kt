package com.jarvis.core.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8B: ActionRiskClassifier — deterministic, side-effect free.
 * risk ≠ permission / ALLOW.
 */
class ActionRiskClassifierTest {

    private val classifier = ActionRiskClassifier()

    @Test
    fun noActionKindIsNone() {
        val a = proposed("no_action", ActionKind.NONE, "—")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.NONE, r.riskClass)
        assertEquals(ActionRiskReasonCode.NO_SIDE_EFFECT, r.reasonCode)
    }

    @Test
    fun readOnlyIsNone() {
        val a = proposed("answer", ActionKind.READ_ONLY, "Ответ")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.NONE, r.riskClass)
        assertEquals(ActionRiskReasonCode.READ_ONLY, r.reasonCode)
    }

    @Test
    fun flashlightIsLow() {
        val a = proposed("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "Фонарик", toolId = "flashlight")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.LOW, r.riskClass)
        assertEquals(ActionRiskReasonCode.LOCAL_REVERSIBLE, r.reasonCode)
    }

    @Test
    fun volumeStepIsLow() {
        val a = proposed("volume_up", ActionKind.LOCAL_DEVICE_SAFE, "Громче", toolId = "volume")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.LOW, r.riskClass)
        assertEquals(ActionRiskReasonCode.LOCAL_REVERSIBLE, r.reasonCode)
    }

    @Test
    fun launchAppIsMedium() {
        val a = proposed("launch_music", ActionKind.LAUNCH_APP, "Music", toolId = "launcher")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.MEDIUM, r.riskClass)
        assertEquals(ActionRiskReasonCode.USER_VISIBLE_EFFECT, r.reasonCode)
    }

    @Test
    fun systemSettingIsMedium() {
        val a = proposed("wifi_toggle", ActionKind.SYSTEM_SETTINGS, "Wi‑Fi")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.MEDIUM, r.riskClass)
        assertEquals(ActionRiskReasonCode.DIRECT_STATE_CHANGE, r.reasonCode)
    }

    @Test
    fun mediaControlIsMedium() {
        val a = proposed("music_play", ActionKind.MEDIA_CONTROL, "Play", toolId = "music")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.MEDIUM, r.riskClass)
    }

    @Test
    fun sendMessageIsHigh() {
        val a = proposed("sms_send", ActionKind.COMMUNICATION, "SMS", toolId = "sms")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.HIGH, r.riskClass)
        assertEquals(ActionRiskReasonCode.EXTERNAL_SIDE_EFFECT, r.reasonCode)
    }

    @Test
    fun deleteDataIsHigh() {
        val a = proposed("delete_data", ActionKind.DATA_DELETION, "Удалить")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.HIGH, r.riskClass)
        assertEquals(ActionRiskReasonCode.DATA_DELETION, r.reasonCode)
    }

    @Test
    fun financialIsCritical() {
        val a = proposed("payment", ActionKind.FINANCIAL, "Оплата")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.CRITICAL, r.riskClass)
        assertEquals(ActionRiskReasonCode.FINANCIAL_EFFECT, r.reasonCode)
    }

    @Test
    fun unknownIsCriticalConservative() {
        val a = proposed("weird_unmapped_xyz", ActionKind.UNKNOWN, "???")
        val r = classifier.classify(a)
        assertEquals(ActionRiskClass.CRITICAL, r.riskClass)
        assertEquals(ActionRiskReasonCode.UNKNOWN_ACTION, r.reasonCode)
        assertNotEquals(ActionRiskClass.LOW, r.riskClass)
        assertNotEquals(ActionRiskClass.NONE, r.riskClass)
    }

    @Test
    fun unknownResolvedFromKnownActionId() {
        val a = proposed("flashlight", ActionKind.UNKNOWN, "Фонарик")
        val r = classifier.classify(a)
        assertEquals(ActionKind.LOCAL_DEVICE_SAFE, r.kind)
        assertEquals(ActionRiskClass.LOW, r.riskClass)
    }

    @Test
    fun deterministicSameInput() {
        val a = proposed("launch_app", ActionKind.LAUNCH_APP, "App")
        val x = classifier.classify(a)
        val y = classifier.classify(a)
        assertEquals(x, y)
    }

    @Test
    fun doesNotMutateProposedAction() {
        val args = mapOf("package" to "com.example")
        val a = ProposedAction(
            actionId = "launch_app",
            kind = ActionKind.LAUNCH_APP,
            displaySummary = "App",
            arguments = args,
        )
        classifier.classify(a)
        assertEquals(ActionKind.LAUNCH_APP, a.kind)
        assertEquals("launch_app", a.actionId)
        assertEquals(args, a.arguments)
    }

    @Test
    fun riskBoundariesOrdered() {
        val none = classifier.classify(proposed("noop", ActionKind.NONE, "-")).riskClass
        val low = classifier.classify(proposed("flashlight", ActionKind.LOCAL_DEVICE_SAFE, "-")).riskClass
        val med = classifier.classify(proposed("launch_app", ActionKind.LAUNCH_APP, "-")).riskClass
        val high = classifier.classify(proposed("sms_send", ActionKind.COMMUNICATION, "-")).riskClass
        val crit = classifier.classify(proposed("payment", ActionKind.FINANCIAL, "-")).riskClass
        assertTrue(none.ordinal < low.ordinal)
        assertTrue(low.ordinal < med.ordinal)
        assertTrue(med.ordinal < high.ordinal)
        assertTrue(high.ordinal < crit.ordinal)
    }

    @Test
    fun uiAutomationIsHighNotLow() {
        val r = classifier.classify(proposed("ui_click", ActionKind.UI_AUTOMATION, "Click"))
        assertEquals(ActionRiskClass.HIGH, r.riskClass)
        assertTrue(r.riskClass.ordinal >= ActionRiskClass.HIGH.ordinal)
    }

    @Test
    fun assessmentIsNotPermission() {
        // Classifier returns assessment only — never AutonomyVerdict / ALLOW
        val high = classifier.classify(proposed("sms_send", ActionKind.COMMUNICATION, "SMS"))
        assertEquals(ActionRiskClass.HIGH, high.riskClass)
        assertEquals(ActionRiskReasonCode.EXTERNAL_SIDE_EFFECT, high.reasonCode)
        // No execution fields on assessment
        assertTrue(high.factors.isNotEmpty())
    }

    private fun proposed(
        id: String,
        kind: ActionKind,
        summary: String,
        toolId: String? = null,
    ): ProposedAction = ProposedAction(
        actionId = id,
        kind = kind,
        displaySummary = summary,
        toolId = toolId,
    )
}
