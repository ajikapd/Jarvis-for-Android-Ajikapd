package com.jarvis.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTextTest {
    @Test fun hidesSpokenCommand() {
        val s = CrashReportText.scrub("I Jarvis.Voice: req=\"позвони маме\" route=FAST")
        assertFalse(s.contains("маме"))
        assertTrue(s.contains("req=<скрыто>"))
    }

    @Test fun hidesKeysEmailsPhones() {
        val s = CrashReportText.scrub("key gsk_abcdef123456 mail me@example.com tel +7 916 123-45-67 AIzaSyD1234567")
        assertFalse(s.contains("abcdef123456"))
        assertFalse(s.contains("example.com"))
        assertFalse(s.contains("916"))
        assertFalse(s.contains("SyD1234567"))
    }

    @Test fun hidesQuotedText() =
        assertEquals("bubble «…» shown", CrashReportText.scrub("bubble «Сэр, Мотор пишет: привет» shown"))

    @Test fun keepsStackTrace() {
        val line = "\tat com.jarvis.app.ui.SectionScreen.bind(More.kt:66)"
        assertEquals(line, CrashReportText.scrub(line))
    }

    @Test fun buildsAndTrims() {
        val r = CrashReportText.build(
            meta = listOf("Устройство" to "POCO F4", "Android" to "14"),
            sections = listOf("Ошибка" to "java.lang.IllegalStateException: boom", "Пусто" to " "),
        )
        assertTrue(r.contains("Устройство: POCO F4"))
        assertTrue(r.contains("== Ошибка =="))
        assertFalse(r.contains("== Пусто =="))
        val long = CrashReportText.build(emptyList(), listOf("Журнал" to "x\n".repeat(50_000)), maxChars = 1000)
        assertTrue(long.length < 1100)
        assertTrue(long.endsWith("обрезано"))
    }
}
