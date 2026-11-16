package com.jarvis.screencontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 4: правила RAM-кэша уведомлений (без Android — проверяется чистый helper).
 * Строки синтетические.
 */
class NotificationPrivacyTest {

    @Test
    fun otpInNotificationTextIsMasked() {
        val out = NotificationPrivacy.sanitize("Ваш код 482913, никому не сообщайте", "com.example.chat")
        assertFalse(out.contains("482913"))
        assertTrue(out.contains("[скрыто]"))
        assertTrue(out.contains("никому не сообщайте"))
    }

    @Test
    fun cardInNotificationTextIsMasked() {
        val out = NotificationPrivacy.sanitize("карта 4111 1111 1111 1111", "com.example.chat")
        assertFalse(out.contains("4111 1111 1111 1111"))
        assertTrue(out.contains("[скрыто]"))
    }

    @Test
    fun ordinaryTextSurvives() {
        val text = "позвони 89161234567 в 15:30"
        assertEquals(text, NotificationPrivacy.sanitize(text, "com.example.chat"))
    }

    @Test
    fun sensitivePackageContentIsNotStored() {
        assertEquals("[скрыто]", NotificationPrivacy.sanitize("Зачисление 1000 ₽", "ru.sberbankmobile"))
        assertEquals("[скрыто]", NotificationPrivacy.sanitize("code 7731", "com.x8bit.bitwarden"))
        assertEquals("", NotificationPrivacy.sanitize("", "ru.sberbankmobile"))
    }

    @Test
    fun textIsTruncatedToLimit() {
        val long = "а".repeat(NotificationPrivacy.MAX_TEXT_CHARS + 50)
        assertEquals(NotificationPrivacy.MAX_TEXT_CHARS, NotificationPrivacy.sanitize(long, "com.example.chat").length)
    }

    @Test
    fun cacheLimitIsFifty() {
        assertEquals(50, NotificationPrivacy.MAX_CACHE_ENTRIES)
    }

    @Test
    fun summaryTitleIsShortAndMasked() {
        val otp = NotificationPrivacy.summaryTitle("Код 482913 подтверждения", "com.example.chat")
        assertFalse(otp.contains("482913"))

        val long = NotificationPrivacy.summaryTitle("б".repeat(100), "com.example.chat")
        assertEquals(41, long.length) // 40 символов + …
    }

    @Test
    fun summaryTitleOfSensitivePackageIsMasked() {
        assertEquals("[скрыто]", NotificationPrivacy.summaryTitle("Оплата прошла", "com.idamob.tinkoff.android"))
    }
}
