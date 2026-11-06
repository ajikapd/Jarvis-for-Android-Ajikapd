package com.jarvis.core.routines

import com.jarvis.core.companion.ObservationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMatcherTest {
    private val headphones = EventRule(EventTrigger.HEADPHONES_ON)
    private val wifiOn = EventRule(EventTrigger.WIFI_ON)
    private val wifiOff = EventRule(EventTrigger.WIFI_OFF)
    private val tiktok = EventRule(EventTrigger.APP_OPEN, "тикток")
    private val rules = listOf(headphones, wifiOn, wifiOff, tiktok)
    private val matcher = EventMatcher({ _, pkg -> pkg == "com.tiktok" }, cooldownMs = 1000)

    private fun net(value: String, at: Long = 0L) =
        matcher.match(PhoneEvent(ObservationType.NETWORK_CHANGED, network = value), rules, at)

    @Test fun firesOnEvent() {
        val hit = matcher.match(PhoneEvent(ObservationType.HEADPHONES_CONNECTED), rules, 0)
        assertEquals(listOf(headphones), hit)
    }

    @Test fun startupStateDoesNotFire() {
        val event = PhoneEvent(ObservationType.HEADPHONES_CONNECTED, startup = true)
        assertTrue(matcher.match(event, rules, 0).isEmpty())
    }

    @Test fun cooldownStopsRepeats() {
        val event = PhoneEvent(ObservationType.HEADPHONES_CONNECTED)
        assertEquals(1, matcher.match(event, rules, 0).size)
        assertTrue(matcher.match(event, rules, 500).isEmpty())
        assertEquals(1, matcher.match(event, rules, 1000).size)
    }

    @Test fun firstNetworkValueIsOnlyRemembered() {
        assertTrue(net("wifi").isEmpty())
        assertTrue(net("wifi").isEmpty())
    }

    @Test fun networkTransitions() {
        net("cellular")
        assertEquals(listOf(wifiOn), net("wifi", 0))
        assertEquals(listOf(wifiOff), net("offline", 2000))
        assertTrue(net("cellular", 4000).isEmpty())
    }

    @Test fun appFiresOncePerOpening() {
        val open = PhoneEvent(ObservationType.APP_FOREGROUND, packageName = "com.tiktok")
        assertEquals(listOf(tiktok), matcher.match(open, rules, 0))
        assertTrue(matcher.match(open, rules, 5000).isEmpty())
        matcher.match(PhoneEvent(ObservationType.APP_FOREGROUND, packageName = "other"), rules, 6000)
        assertEquals(listOf(tiktok), matcher.match(open, rules, 7000))
    }

    @Test fun otherAppDoesNotFire() {
        val open = PhoneEvent(ObservationType.APP_FOREGROUND, packageName = "other")
        assertTrue(matcher.match(open, rules, 0).isEmpty())
    }

    @Test fun nameIncludesApp() {
        assertEquals("открыто приложение тикток", tiktok.name)
        assertEquals("подключены наушники", headphones.name)
    }
}
