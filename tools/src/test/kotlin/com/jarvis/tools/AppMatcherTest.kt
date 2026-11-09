package com.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        "org.telegram.messenger" to "Telegram",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.youtube" to "YouTube",
        "ru.yandex.music" to "Яндекс Музыка",
        "com.vkontakte.android" to "VK",
        "com.android.settings" to "Настройки",
        "com.miui.calculator" to "Калькулятор",
        "com.spotify.music" to "Spotify",
        "com.instagram.android" to "Instagram",
    )

    private fun pick(q: String) = AppMatcher.best(q, apps)

    @Test fun russianSpellingOfEnglishNames() {
        assertEquals("com.whatsapp.w4b", pick("ватсапп бизнес"))
        assertEquals("com.whatsapp", pick("ватсап"))
        assertEquals("org.telegram.messenger", pick("телеграм"))
        assertEquals("com.google.android.youtube", pick("ютуб"))
        assertEquals("com.spotify.music", pick("спотифай"))
        assertEquals("com.instagram.android", pick("инстаграм"))
    }

    @Test fun nativeNamesAndCaseForms() {
        assertEquals("ru.yandex.music", pick("яндекс музыку"))
        assertEquals("com.miui.calculator", pick("калькулятор"))
        assertEquals("com.android.settings", pick("настройки"))
    }

    @Test fun unrelatedIsRejected() {
        assertNull(pick("фонарик"))
        assertNull(pick("банк"))
    }

    @Test fun keyMergesSpellings() {
        assertEquals(AppMatcher.key("whatsapp"), AppMatcher.key("ватсапп"))
        assertTrue(AppMatcher.score("бизнес", "Business") >= AppMatcher.ACCEPT)
    }
}
