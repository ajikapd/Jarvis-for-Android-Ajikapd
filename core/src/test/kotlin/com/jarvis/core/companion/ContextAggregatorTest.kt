package com.jarvis.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextAggregatorTest {

    private lateinit var aggregator: ContextAggregator

    @Before
    fun setUp() {
        aggregator = ContextAggregator(
            ContextAggregator.Config(
                sameFgDebounceMs = 1_500L,
                windowCoalesceMs = 2_500L,
                batteryCoalesceMs = 2_500L,
                networkCoalesceMs = 3_000L,
                notificationDebounceMs = 1_200L,
                usageStatsStaleOverrideMs = 45_000L,
            ),
        )
    }

    @Test
    fun accessibilityFgUpdatesContextAndProvenance() {
        val o = PhoneObservation(
            timestampMillis = 1_000L,
            source = ObservationSource.ACCESSIBILITY,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
            confidence = 0.9f,
            summary = "chrome",
        )
        val r = aggregator.aggregate(PhoneContext(), o)
        assertTrue(r.accepted)
        assertEquals("com.android.chrome", r.context.currentApp)
        assertEquals(ObservationSource.ACCESSIBILITY, r.context.currentAppSource)
        assertEquals(0.9f, r.context.currentAppConfidence)
        assertEquals(1_000L, r.context.currentAppAtMillis)
    }

    @Test
    fun usageStatsBlockedWhileAccessibilityFresh() {
        val a11y = PhoneObservation(
            timestampMillis = 10_000L,
            source = ObservationSource.ACCESSIBILITY,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
            confidence = 0.9f,
        )
        val afterA11y = aggregator.aggregate(PhoneContext(), a11y).context
        val usage = PhoneObservation(
            timestampMillis = 12_000L,
            source = ObservationSource.USAGE_STATS,
            type = ObservationType.APP_FOREGROUND,
            packageName = "org.telegram.messenger",
            confidence = 0.7f,
        )
        val r = aggregator.aggregate(afterA11y, usage)
        assertFalse(r.accepted)
        assertEquals("usage_stats_blocked_by_a11y", r.dropReason)
        assertEquals("com.android.chrome", r.context.currentApp)
        assertEquals(ObservationSource.ACCESSIBILITY, r.context.currentAppSource)
    }

    @Test
    fun usageStatsDuplicateSameAppDropped() {
        val a11y = PhoneObservation(
            timestampMillis = 10_000L,
            source = ObservationSource.ACCESSIBILITY,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
            confidence = 0.9f,
        )
        val afterA11y = aggregator.aggregate(PhoneContext(), a11y).context
        val usage = PhoneObservation(
            timestampMillis = 11_000L,
            source = ObservationSource.USAGE_STATS,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
            confidence = 0.7f,
        )
        val r = aggregator.aggregate(afterA11y, usage)
        assertFalse(r.accepted)
        assertEquals("usage_stats_dup_a11y", r.dropReason)
    }

    @Test
    fun usageStatsAllowedWhenAccessibilityStale() {
        val a11y = PhoneObservation(
            timestampMillis = 10_000L,
            source = ObservationSource.ACCESSIBILITY,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
            confidence = 0.9f,
        )
        val afterA11y = aggregator.aggregate(PhoneContext(), a11y).context
        val usage = PhoneObservation(
            timestampMillis = 10_000L + 46_000L,
            source = ObservationSource.USAGE_STATS,
            type = ObservationType.APP_FOREGROUND,
            packageName = "org.telegram.messenger",
            confidence = 0.7f,
        )
        val r = aggregator.aggregate(afterA11y, usage)
        assertTrue(r.accepted)
        assertEquals("org.telegram.messenger", r.context.currentApp)
        assertEquals(ObservationSource.USAGE_STATS, r.context.currentAppSource)
    }

    @Test
    fun usageStatsBackupWhenNoAppYet() {
        val usage = PhoneObservation(
            timestampMillis = 5_000L,
            source = ObservationSource.USAGE_STATS,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.settings",
            confidence = 0.7f,
        )
        val r = aggregator.aggregate(PhoneContext(), usage)
        assertTrue(r.accepted)
        assertEquals("com.android.settings", r.context.currentApp)
        assertEquals(ObservationSource.USAGE_STATS, r.context.currentAppSource)
    }

    @Test
    fun fgDebounceDropsRapidSamePackage() {
        val first = PhoneObservation(
            timestampMillis = 1_000L,
            source = ObservationSource.ACCESSIBILITY,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
        )
        val ctx = aggregator.aggregate(PhoneContext(), first).context
        val second = PhoneObservation(
            timestampMillis = 1_400L,
            source = ObservationSource.ACCESSIBILITY,
            type = ObservationType.APP_FOREGROUND,
            packageName = "com.android.chrome",
        )
        val r = aggregator.aggregate(ctx, second)
        assertFalse(r.accepted)
        assertEquals("fg_debounce", r.dropReason)
        assertEquals(1L, r.context.observationCount)
    }

    @Test
    fun batteryCoalesceDropsIdenticalBurst() {
        val a = PhoneObservation(
            timestampMillis = 1_000L,
            source = ObservationSource.SYSTEM_BROADCAST,
            type = ObservationType.BATTERY_CHANGED,
            entities = mapOf("percent" to "80", "charging" to "true"),
            summary = "80%",
        )
        val ctx = aggregator.aggregate(PhoneContext(), a).context
        val b = PhoneObservation(
            timestampMillis = 1_500L,
            source = ObservationSource.SYSTEM_BROADCAST,
            type = ObservationType.BATTERY_CHANGED,
            entities = mapOf("percent" to "80", "charging" to "true"),
            summary = "80%",
        )
        val r = aggregator.aggregate(ctx, b)
        assertFalse(r.accepted)
        assertEquals("battery_coalesce", r.dropReason)
    }

    @Test
    fun previousAppTrackedOnSwitch() {
        val a = aggregator.aggregate(
            PhoneContext(),
            PhoneObservation(
                timestampMillis = 1L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "a.pkg",
            ),
        ).context
        val b = aggregator.aggregate(
            a,
            PhoneObservation(
                timestampMillis = 2_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "b.pkg",
            ),
        )
        assertTrue(b.accepted)
        assertEquals("b.pkg", b.context.currentApp)
        assertEquals("a.pkg", b.context.previousApp)
    }

    @Test
    fun chromeFgStormCollapsedToOne() {
        var ctx = PhoneContext()
        var accepted = 0
        // Simulate a11y WINDOW_STATE + WINDOWS_CHANGED + UsageStats burst
        val events = listOf(
            PhoneObservation(
                timestampMillis = 10_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "com.android.chrome",
                confidence = 0.9f,
            ),
            PhoneObservation(
                timestampMillis = 10_200L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "com.android.chrome",
                screenId = "windows_changed",
                confidence = 0.9f,
            ),
            PhoneObservation(
                timestampMillis = 10_400L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.WINDOW_CHANGED,
                packageName = "com.android.chrome",
                screenId = "Main",
                entities = mapOf("kind" to "window_state"),
                confidence = 0.8f,
            ),
            PhoneObservation(
                timestampMillis = 10_600L,
                source = ObservationSource.USAGE_STATS,
                type = ObservationType.APP_FOREGROUND,
                packageName = "com.android.chrome",
                confidence = 0.7f,
            ),
            PhoneObservation(
                timestampMillis = 10_800L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.WINDOW_CHANGED,
                packageName = "com.android.chrome",
                screenId = "Main",
                entities = mapOf("kind" to "window_state"),
                confidence = 0.8f,
            ),
        )
        for (e in events) {
            val r = aggregator.aggregate(ctx, e)
            if (r.accepted) {
                accepted++
                ctx = r.context
            }
        }
        assertEquals(1, accepted)
        assertEquals("com.android.chrome", ctx.currentApp)
        assertEquals(ObservationSource.ACCESSIBILITY, ctx.currentAppSource)
    }

    @Test
    fun windowContentCoalescedSeparately() {
        var ctx = aggregator.aggregate(
            PhoneContext(),
            PhoneObservation(
                timestampMillis = 1_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "com.android.chrome",
            ),
        ).context
        val w1 = aggregator.aggregate(
            ctx,
            PhoneObservation(
                timestampMillis = 3_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.WINDOW_CHANGED,
                packageName = "com.android.chrome",
                screenId = "Tabs",
                entities = mapOf("kind" to "content"),
                confidence = 0.55f,
            ),
        )
        assertTrue(w1.accepted)
        ctx = w1.context
        val w2 = aggregator.aggregate(
            ctx,
            PhoneObservation(
                timestampMillis = 3_500L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.WINDOW_CHANGED,
                packageName = "com.android.chrome",
                screenId = "Tabs",
                entities = mapOf("kind" to "content"),
                confidence = 0.55f,
            ),
        )
        assertFalse(w2.accepted)
        assertEquals("window_coalesce", w2.dropReason)
    }
}

class PhoneContextBusHardeningTest {

    @Before
    fun setUp() {
        PhoneContextBus.resetForTests()
    }

    @Test
    fun snapshotConsistentWithAcceptedEvents() {
        assertTrue(
            PhoneContextBus.publish(
                PhoneObservation(
                    id = "obs-1",
                    timestampMillis = 100L,
                    source = ObservationSource.SYSTEM_BROADCAST,
                    type = ObservationType.BATTERY_CHANGED,
                    entities = mapOf("percent" to "50", "charging" to "false"),
                    summary = "50%",
                    confidence = 1f,
                ),
            ),
        )
        assertTrue(
            PhoneContextBus.publish(
                PhoneObservation(
                    id = "obs-2",
                    timestampMillis = 200L,
                    source = ObservationSource.ACCESSIBILITY,
                    type = ObservationType.APP_FOREGROUND,
                    packageName = "com.jarvis.assistant",
                    confidence = 0.9f,
                ),
            ),
        )
        val snap = PhoneContextBus.snapshot()
        assertEquals(50, snap.context.batteryPercent)
        assertEquals("com.jarvis.assistant", snap.context.currentApp)
        assertEquals("obs-2", snap.lastAcceptedObservationId)
        assertEquals(200L, snap.lastAcceptedAtMillis)
        assertEquals(ObservationSource.ACCESSIBILITY, snap.lastAcceptedSource)
        assertEquals(ObservationType.APP_FOREGROUND, snap.lastAcceptedType)
        assertEquals(2L, snap.context.observationCount)
        assertNotNull(snap.takenAtMillis)
    }

    @Test
    fun droppedEventsDoNotEnterDiagnosticsOrCount() {
        PhoneContextBus.publish(
            PhoneObservation(
                timestampMillis = 1_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "com.android.chrome",
            ),
        )
        val dropped = PhoneContextBus.publish(
            PhoneObservation(
                timestampMillis = 1_200L,
                source = ObservationSource.USAGE_STATS,
                type = ObservationType.APP_FOREGROUND,
                packageName = "org.telegram.messenger",
            ),
        )
        assertFalse(dropped)
        assertEquals(1, PhoneContextBus.recent().size)
        assertEquals(1L, PhoneContextBus.current().observationCount)
        assertEquals(ObservationSource.ACCESSIBILITY, PhoneContextBus.current().currentAppSource)
    }

    @Test
    fun diagnosticsBufferSeparateFromContext() {
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.SYSTEM_BROADCAST,
                type = ObservationType.SCREEN_OFF,
                summary = "off",
            ),
        )
        val ctx = PhoneContextBus.current()
        assertFalse(ctx.screenOn)
        assertEquals(1, ObservationDiagnosticsBuffer.recent().size)
        // Context has no embedded history list — only counters/fields
        assertNull(ctx.currentApp)
        assertEquals(1L, ctx.observationCount)
    }

    @Test
    fun observationKeepsProvenanceAndConfidence() {
        val o = PhoneObservation(
            source = ObservationSource.NOTIFICATION_LISTENER,
            type = ObservationType.NOTIFICATION_POSTED,
            packageName = "com.android.shell",
            summary = "Уведомление: com.android.shell · Hi",
            confidence = 0.85f,
        )
        assertEquals(o.source, o.provenance)
        assertEquals(0.85f, o.confidence)
        assertTrue(PhoneContextBus.publish(o))
        assertEquals("com.android.shell", PhoneContextBus.current().lastNotificationPackage)
    }
}
