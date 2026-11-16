package com.jarvis.core.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultResultVerifierTest {

    private val verifier = DefaultResultVerifier()

    @Test
    fun successWhenObservedMatchesExpected() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolId = "flashlight",
                toolResultStatus = "SUCCESS",
                expectedEffects = ExpectedEffects.forTool("flashlight", mapOf("action" to "on")),
                observed = ObservedState(facts = mapOf("flashlight" to "on")),
            ),
        )
        assertEquals(VerificationStatus.SUCCESS, r.status)
        assertTrue(r.ok)
        assertEquals(listOf("flashlight"), r.matched)
    }

    @Test
    fun failureWhenObservedMismatches() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolId = "flashlight",
                toolResultStatus = "SUCCESS",
                expectedEffects = listOf(ExpectedEffect("flashlight", "on")),
                observed = ObservedState(facts = mapOf("flashlight" to "off")),
            ),
        )
        assertEquals(VerificationStatus.FAILURE, r.status)
        assertFalse(r.ok)
        assertTrue(r.mismatched.isNotEmpty())
    }

    @Test
    fun unknownWithoutObservedForRequiredEffects() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolId = "flashlight",
                toolResultStatus = "SUCCESS",
                expectedEffects = listOf(ExpectedEffect("flashlight", "on")),
                observed = null,
            ),
        )
        assertEquals(VerificationStatus.UNKNOWN, r.status)
        assertEquals(listOf("flashlight"), r.missing)
    }

    @Test
    fun partialWhenOneOfTwoMatches() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolResultStatus = "SUCCESS",
                expectedEffects = listOf(
                    ExpectedEffect("flashlight", "on"),
                    ExpectedEffect("volume_action_applied", "up"),
                ),
                observed = ObservedState(
                    facts = mapOf("flashlight" to "on", "volume_action_applied" to "down"),
                ),
            ),
        )
        assertEquals(VerificationStatus.PARTIAL_SUCCESS, r.status)
        assertTrue(r.ok)
        assertEquals(listOf("flashlight"), r.matched)
    }

    @Test
    fun retryableOnFailedTool() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolResultStatus = "FAILED",
                toolResultMessage = "timeout",
            ),
        )
        assertEquals(VerificationStatus.RETRYABLE, r.status)
    }

    @Test
    fun irreversibleOnFailedDestructive() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolResultStatus = "FAILED",
                irreversibleAction = true,
            ),
        )
        assertEquals(VerificationStatus.IRREVERSIBLE, r.status)
    }

    @Test
    fun successWithNoEffectsWhenToolOk() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolResultStatus = "SUCCESS",
            ),
        )
        assertEquals(VerificationStatus.SUCCESS, r.status)
    }

    @Test
    fun dryRunReadyCountsAsToolOk() {
        val r = verifier.verify(
            VerificationRequest(
                stepId = "s1",
                toolResultStatus = "DRY_RUN_READY",
                expectedEffects = emptyList(),
            ),
        )
        assertEquals(VerificationStatus.SUCCESS, r.status)
    }

    @Test
    fun expectedEffectsForVolume() {
        val effects = ExpectedEffects.forTool("volume", mapOf("action" to "mute"))
        assertEquals(1, effects.size)
        assertEquals("volume_action_applied", effects[0].key)
        assertFalse(effects[0].required)
    }
}
