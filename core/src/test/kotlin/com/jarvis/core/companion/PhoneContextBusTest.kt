package com.jarvis.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Smoke / regression поверх шины после Stage 3.5 hardening. */
class PhoneContextBusTest {

    @Before
    fun setUp() {
        PhoneContextBus.resetForTests()
    }

    @Test
    fun screenAndBatteryUpdateContext() {
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.SYSTEM_BROADCAST,
                type = ObservationType.SCREEN_OFF,
                summary = "off",
            ),
        )
        PhoneContextBus.publish(
            PhoneObservation(
                timestampMillis = 5_000L,
                source = ObservationSource.SYSTEM_BROADCAST,
                type = ObservationType.BATTERY_CHANGED,
                summary = "42%",
                entities = mapOf("percent" to "42", "charging" to "true"),
            ),
        )
        val ctx = PhoneContextBus.current()
        assertFalse(ctx.screenOn)
        assertEquals(42, ctx.batteryPercent)
        assertTrue(ctx.charging)
        assertEquals(2, ctx.observationCount)
        assertEquals(2, PhoneContextBus.snapshot().context.observationCount)
    }

    @Test
    fun appForegroundTracksPrevious() {
        PhoneContextBus.publish(
            PhoneObservation(
                timestampMillis = 1_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "org.telegram.messenger",
                summary = "tg",
            ),
        )
        PhoneContextBus.publish(
            PhoneObservation(
                timestampMillis = 3_000L,
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = "com.android.chrome",
                summary = "chrome",
            ),
        )
        val ctx = PhoneContextBus.current()
        assertEquals("com.android.chrome", ctx.currentApp)
        assertEquals("org.telegram.messenger", ctx.previousApp)
        assertEquals(2, PhoneContextBus.recent().size)
        assertEquals(ObservationSource.ACCESSIBILITY, ctx.currentAppSource)
    }

    @Test
    fun notificationPostedUpdatesContext() {
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.NOTIFICATION_LISTENER,
                type = ObservationType.NOTIFICATION_POSTED,
                packageName = "org.telegram.messenger",
                summary = "Уведомление: org.telegram.messenger · Hello",
            ),
        )
        val ctx = PhoneContextBus.current()
        assertEquals("org.telegram.messenger", ctx.lastNotificationPackage)
        assertTrue(ctx.lastNotificationSummary!!.contains("Hello"))
    }
}
