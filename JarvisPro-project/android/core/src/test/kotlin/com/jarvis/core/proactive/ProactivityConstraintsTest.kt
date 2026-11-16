package com.jarvis.core.proactive

import com.jarvis.core.behavior.BehaviorFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7C: CooldownPolicy + ProactivityBudget + State + Codec.
 */
class ProactivityConstraintsTest {

    private val config = ProactivityConfig(
        globalCooldownMs = 10_000L,
        behaviorCooldownMs = 20_000L,
        fingerprintCooldownMs = 15_000L,
        rejectCooldownMs = 30_000L,
        budgetMaxTokens = 3,
        budgetRefillIntervalMs = 10_000L,
        maxFingerprints = 4,
        maxBehaviorCooldowns = 4,
        maxRejectCooldowns = 4,
    )
    private val cooldown = CooldownPolicy(config)
    private val budget = ProactivityBudget(config)

    // —— Cooldown ——

    @Test
    fun globalCooldownActiveThenExpired() {
        var state = ProactivityState.initial(config)
        val t0 = 1_000_000L
        state = cooldown.afterProactiveDecision(
            state, t0, "seq:HP→music", ProactiveDecisionType.SUGGESTION,
        )
        val blocked = cooldown.canConsider("other:key", t0 + 1_000L, state, BehaviorFeedbackView())
        assertFalse(blocked.allowed)
        assertEquals(ProactiveReasonCode.GLOBAL_COOLDOWN, blocked.reasonCode)

        val ok = cooldown.canConsider("other:key", t0 + config.globalCooldownMs, state, BehaviorFeedbackView())
        assertTrue(ok.allowed)
    }

    @Test
    fun behaviorCooldownIndependentKeys() {
        var state = ProactivityState.initial(config)
        val t0 = 2_000_000L
        state = cooldown.afterProactiveDecision(
            state, t0, "keyA", ProactiveDecisionType.QUESTION, fingerprint = "fpA",
        )
        // global blocks everyone briefly — jump past global
        val t1 = t0 + config.globalCooldownMs
        val a = cooldown.canConsider("keyA", t1, state, BehaviorFeedbackView())
        assertFalse(a.allowed)
        assertEquals(ProactiveReasonCode.BEHAVIOR_COOLDOWN, a.reasonCode)

        val b = cooldown.canConsider("keyB", t1, state, BehaviorFeedbackView())
        assertTrue(b.allowed)
    }

    @Test
    fun fingerprintCooldown() {
        var state = ProactivityState.initial(config)
        val t0 = 3_000_000L
        state = cooldown.afterProactiveDecision(
            state, t0, "keyA", ProactiveDecisionType.CONFIRMATION_REQUEST, fingerprint = "fp1",
        )
        val tAfterGlobal = t0 + config.globalCooldownMs
        val r = cooldown.canConsider("keyB", tAfterGlobal, state, BehaviorFeedbackView(), fingerprint = "fp1")
        assertFalse(r.allowed)
        assertEquals(ProactiveReasonCode.DUPLICATE, r.reasonCode)

        val afterFp = cooldown.canConsider(
            "keyB",
            t0 + config.fingerprintCooldownMs,
            state,
            BehaviorFeedbackView(),
            fingerprint = "fp1",
        )
        assertTrue(afterFp.allowed)
    }

    @Test
    fun ignoreUsesBehaviorStoreNotDuplicateDisable() {
        val t0 = 4_000_000L
        val feedback = BehaviorFeedbackView(
            ignoreUntil = mapOf("keyX" to t0 + 6 * 3_600_000L),
        )
        val state = ProactivityState.initial(config)
        val r = cooldown.canConsider("keyX", t0 + 1_000L, state, feedback)
        assertFalse(r.allowed)
        assertEquals(ProactiveReasonCode.FEEDBACK_COOLDOWN, r.reasonCode)
        assertEquals("ignore_until", r.detail)

        // afterFeedback(IGNORE) does not write timed disable into state
        val after = cooldown.afterFeedback(state, t0, "keyX", BehaviorFeedback.IGNORE)
        assertTrue(after.rejectCooldownUntil.isEmpty())
        assertTrue(after.behaviorCooldownUntil.isEmpty())
    }

    @Test
    fun disableIsPermanentViaStoreNotTimedCooldown() {
        val feedback = BehaviorFeedbackView(disabledKeys = setOf("keyD"))
        val state = ProactivityState.initial(config)
        val r = cooldown.canConsider("keyD", 5_000_000L, state, feedback)
        assertFalse(r.allowed)
        assertEquals(ProactiveReasonCode.DISABLED, r.reasonCode)

        val after = cooldown.afterFeedback(state, 5_000_000L, "keyD", BehaviorFeedback.DISABLE)
        assertFalse(after.behaviorCooldownUntil.containsKey("keyD"))
        assertFalse(after.rejectCooldownUntil.containsKey("keyD"))
    }

    @Test
    fun acceptDoesNotCreateDisableOrCooldown() {
        val state = ProactivityState.initial(config)
        val after = cooldown.afterFeedback(state, 6_000_000L, "keyA", BehaviorFeedback.ACCEPT)
        assertEquals(state.behaviorCooldownUntil, after.behaviorCooldownUntil)
        assertEquals(state.rejectCooldownUntil, after.rejectCooldownUntil)
        assertEquals(0L, after.globalCooldownUntilMillis)
    }

    @Test
    fun rejectAddsSoftCooldownAndBlocksViaRejectedKeys() {
        val t0 = 7_000_000L
        var state = ProactivityState.initial(config)
        state = cooldown.afterFeedback(state, t0, "keyR", BehaviorFeedback.REJECT)
        assertTrue(state.rejectCooldownUntil.containsKey("keyR"))

        val withRejected = BehaviorFeedbackView(rejectedKeys = setOf("keyR"))
        val r = cooldown.canConsider("keyR", t0 + 1_000L, state, withRejected)
        assertFalse(r.allowed)
        assertEquals(ProactiveReasonCode.FEEDBACK_COOLDOWN, r.reasonCode)
    }

    @Test
    fun boundaryTimestampGlobalCooldown() {
        var state = ProactivityState.initial(config)
        val t0 = 8_000_000L
        state = cooldown.afterProactiveDecision(
            state, t0, "k", ProactiveDecisionType.SUGGESTION,
        )
        val until = state.globalCooldownUntilMillis
        val atBoundary = cooldown.canConsider("other", until, state, BehaviorFeedbackView())
        assertTrue(atBoundary.allowed)
        val before = cooldown.canConsider("other", until - 1L, state, BehaviorFeedbackView())
        assertFalse(before.allowed)
    }

    @Test
    fun noOpDoesNotArmCooldown() {
        val state = ProactivityState.initial(config)
        val after = cooldown.afterProactiveDecision(
            state, 9_000_000L, "k", ProactiveDecisionType.NO_OP,
        )
        assertEquals(0L, after.globalCooldownUntilMillis)
        assertTrue(after.behaviorCooldownUntil.isEmpty())
    }

    // —— Budget ——

    @Test
    fun budgetInitialConsumeExhaustRefill() {
        var state = ProactivityState.initial(config)
        val t0 = 10_000_000L
        state = budget.withRefill(state, t0)
        assertEquals(3, budget.remaining(state, t0))
        assertTrue(budget.available(state, t0))

        state = budget.record(state, t0, ProactiveDecisionType.SUGGESTION)
        state = budget.record(state, t0, ProactiveDecisionType.QUESTION)
        state = budget.record(state, t0, ProactiveDecisionType.CONFIRMATION_REQUEST)
        assertEquals(0, budget.remaining(state, t0))
        assertFalse(budget.available(state, t0))

        // refill one token after interval
        state = budget.withRefill(state, t0 + config.budgetRefillIntervalMs)
        assertEquals(1, budget.remaining(state, t0 + config.budgetRefillIntervalMs))
        assertTrue(budget.available(state, t0 + config.budgetRefillIntervalMs))
    }

    @Test
    fun budgetRefillBoundaryAndCap() {
        var state = ProactivityState.initial(config).copy(
            budgetTokens = 0,
            budgetLastRefillAtMillis = 11_000_000L,
        )
        val justBefore = budget.withRefill(state, 11_000_000L + config.budgetRefillIntervalMs - 1)
        assertEquals(0, justBefore.budgetTokens)

        val atBoundary = budget.withRefill(state, 11_000_000L + config.budgetRefillIntervalMs)
        assertEquals(1, atBoundary.budgetTokens)

        // many intervals cannot exceed max
        val many = budget.withRefill(state, 11_000_000L + config.budgetRefillIntervalMs * 100)
        assertEquals(config.budgetMaxTokens, many.budgetTokens)
    }

    @Test
    fun budgetNoOpDoesNotConsume() {
        var state = ProactivityState.initial(config)
        val t0 = 12_000_000L
        state = budget.withRefill(state, t0)
        val before = state.budgetTokens
        state = budget.record(state, t0, ProactiveDecisionType.NO_OP)
        assertEquals(before, state.budgetTokens)
        assertTrue(state.recordedNoOpCount >= 1)
    }

    @Test
    fun budgetRollbackDoesNotGrantTokens() {
        var state = ProactivityState.initial(config).copy(
            budgetTokens = 1,
            budgetLastRefillAtMillis = 13_000_000L,
        )
        state = budget.withRefill(state, 12_000_000L) // rollback
        assertEquals(1, state.budgetTokens)
        assertEquals(13_000_000L, state.budgetLastRefillAtMillis)
    }

    @Test
    fun budgetRecordWhenExhaustedLeavesState() {
        var state = ProactivityState.initial(config).copy(
            budgetTokens = 0,
            budgetLastRefillAtMillis = 14_000_000L,
        )
        val after = budget.record(state, 14_000_000L, ProactiveDecisionType.SUGGESTION)
        assertEquals(0, after.budgetTokens)
        assertEquals(0, after.suggestionCount)
    }

    // —— State bounds ——

    @Test
    fun fingerprintsBounded() {
        var state = ProactivityState.initial(config)
        val t0 = 15_000_000L
        repeat(10) { i ->
            state = cooldown.afterProactiveDecision(
                state,
                t0 + i * (config.globalCooldownMs + 1),
                "k$i",
                ProactiveDecisionType.SUGGESTION,
                fingerprint = "fp$i",
            )
        }
        assertTrue(state.fingerprintCooldownUntil.size <= config.maxFingerprints)
        assertTrue(state.behaviorCooldownUntil.size <= config.maxBehaviorCooldowns)
    }

    @Test
    fun pruneRemovesExpired() {
        val t0 = 16_000_000L
        val state = ProactivityState(
            behaviorCooldownUntil = mapOf("a" to t0 - 1, "b" to t0 + 1000),
            fingerprintCooldownUntil = mapOf("f" to t0 - 5),
        )
        val pruned = state.pruneExpired(t0, config)
        assertFalse(pruned.behaviorCooldownUntil.containsKey("a"))
        assertTrue(pruned.behaviorCooldownUntil.containsKey("b"))
        assertTrue(pruned.fingerprintCooldownUntil.isEmpty())
    }

    @Test
    fun copySemanticsImmutable() {
        val a = ProactivityState.initial(config)
        val b = a.copy(budgetTokens = 1)
        assertEquals(config.budgetMaxTokens, a.budgetTokens)
        assertEquals(1, b.budgetTokens)
    }

    // —— Codec ——

    @Test
    fun codecRoundTripDeterministic() {
        val state = ProactivityState(
            lastDecisionAtMillis = 100L,
            lastDecisionType = ProactiveDecisionType.SUGGESTION,
            globalCooldownUntilMillis = 200L,
            behaviorCooldownUntil = mapOf("k1" to 300L),
            fingerprintCooldownUntil = mapOf("fp" to 400L),
            rejectCooldownUntil = mapOf("r" to 500L),
            budgetTokens = 2,
            budgetLastRefillAtMillis = 150L,
            suggestionCount = 3,
            questionCount = 1,
            confirmationCount = 2,
            recordedNoOpCount = 9,
        )
        val enc1 = ProactivitySnapshotCodec.encode(state)
        val enc2 = ProactivitySnapshotCodec.encode(state)
        assertEquals(enc1, enc2)
        val decoded = ProactivitySnapshotCodec.decode(enc1)
        assertEquals(state, decoded)
    }

    @Test
    fun codecMalformedUnknownEmptySafe() {
        assertEquals(ProactivityState.initial().budgetTokens, ProactivitySnapshotCodec.decode("").budgetTokens)
        assertEquals(
            ProactivityState.initial().budgetTokens,
            ProactivitySnapshotCodec.decode("v999\nfoo=bar").budgetTokens,
        )
        assertEquals(
            ProactivityState.initial().budgetTokens,
            ProactivitySnapshotCodec.decode("not-a-version\ngarbage").budgetTokens,
        )
        val partial = ProactivitySnapshotCodec.decode("v1\nbudgetTokens=2\nbrokenline\n")
        assertEquals(2, partial.budgetTokens)
    }

    @Test
    fun codecNoSensitivePayload() {
        val enc = ProactivitySnapshotCodec.encode(
            ProactivityState(behaviorCooldownUntil = mapOf("seq:HEADPHONES→music" to 1L)),
        )
        assertFalse(enc.contains("OTP"))
        assertFalse(enc.contains("password"))
        assertTrue(enc.startsWith("v1"))
    }

    @Test
    fun codecBoundsRestoredMaps() {
        val huge = (0 until 100).associate { "k$it" to (it.toLong() + 1) }
        val encoded = buildString {
            appendLine("v1")
            appendLine(
                "behaviorCooldown=" + huge.entries.joinToString(",") { "${it.key}@${it.value}" },
            )
        }
        val decoded = ProactivitySnapshotCodec.decode(encoded)
        assertTrue(decoded.behaviorCooldownUntil.size <= ProactivityConfig.DEFAULT.maxBehaviorCooldowns)
    }
}
