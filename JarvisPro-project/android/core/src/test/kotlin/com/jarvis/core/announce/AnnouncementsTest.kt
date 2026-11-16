package com.jarvis.core.announce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementsTest {
    private val tg = "org.telegram.messenger"

    @Test fun reads() = assertEquals(
        "Сэр, Дима в Телеграме: буду через час.",
        MessageAnnouncer().phrase(IncomingMessage(tg, "Дима", "Буду через час."), 0)?.replace("Буду", "буду"),
    )

    @Test fun counterInTitleIsDropped() =
        assertEquals("Дима", MessageAnnouncer.cleanSender("Дима (3 сообщения)"))

    @Test fun otherAppsStaySilent() = assertNull(MessageAnnouncer().phrase(IncomingMessage("com.bank", "Банк", "x"), 0))
    @Test fun groupsStaySilent() = assertNull(MessageAnnouncer().phrase(IncomingMessage(tg, "Чат", "x", group = true), 0))

    @Test fun burstIsLimited() {
        val a = MessageAnnouncer(minGapMs = 1000)
        assertTrue(a.phrase(IncomingMessage(tg, "Дима", "раз"), 0) != null)
        assertNull(a.phrase(IncomingMessage(tg, "Дима", "два"), 500))
        assertTrue(a.phrase(IncomingMessage(tg, "Дима", "три"), 2000) != null)
    }

    @Test fun repeatedNotificationIsSpokenOnce() {
        val a = MessageAnnouncer(minGapMs = 0)
        assertTrue(a.phrase(IncomingMessage(tg, "Дима", "раз"), 0) != null)
        assertNull(a.phrase(IncomingMessage(tg, "Дима", "раз"), 5000))
    }

    @Test fun call() {
        assertEquals("Сэр, звонит мама.", CallAnnouncer.phrase("мама"))
        assertEquals("Сэр, звонок с неизвестного номера.", CallAnnouncer.phrase(null))
    }

    @Test fun quiet() {
        assertTrue(QuietHours.isQuiet(23))
        assertTrue(QuietHours.isQuiet(7))
        assertFalse(QuietHours.isQuiet(8))
    }
}
