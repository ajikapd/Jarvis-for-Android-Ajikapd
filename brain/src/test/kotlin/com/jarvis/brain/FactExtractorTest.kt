package com.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 3: очистка фразы, узкие шаблоны и отказ на секреты.
 * Строки синтетические.
 */
class FactExtractorTest {

    private fun saved(text: String): String? =
        (FactExtractor.decide(text) as? FactExtractor.Decision.Save)?.text

    private fun refused(text: String): Boolean =
        FactExtractor.decide(text) == FactExtractor.Decision.Refused

    // ─────────────────────────────────── очистка вводных конструкций

    @Test
    fun rememberPrefixIsRemoved() {
        assertEquals("Пользователь любит зелёный чай", saved("запомни что я люблю зелёный чай"))
    }

    @Test
    fun rememberPrefixWithCommaIsRemoved() {
        assertEquals("Пользователь любит зелёный чай", saved("Запомни, что я люблю зелёный чай"))
    }

    @Test
    fun bareRememberPrefixIsRemoved() {
        assertEquals("Мой день рождения 15 марта", saved("запомни мой день рождения 15 марта"))
    }

    @Test
    fun writePrefixIsRemoved() {
        assertEquals("Пользователь живёт в Москве", saved("запиши что я живу в Москве"))
    }

    @Test
    fun doNotForgetPrefixIsRemoved() {
        assertEquals("Пользователь работает в Яндексе", saved("не забудь что я работаю в Яндексе"))
    }

    @Test
    fun keepInMindPrefixIsRemoved() {
        assertEquals("Пользователь предпочитает тишину", saved("имей в виду что я предпочитаю тишину"))
    }

    @Test
    fun englishRememberThatIsRemoved() {
        assertEquals("Пользователь любит зелёный чай", saved("remember that я люблю зелёный чай"))
        assertEquals("I like green tea", saved("remember that I like green tea"))
    }

    // ─────────────────────────────────── шаблоны

    @Test
    fun likesTemplate() {
        assertEquals("Пользователь любит кофе", saved("я люблю кофе"))
    }

    @Test
    fun dislikesTemplateWinsOverLikes() {
        assertEquals("Пользователь не любит кофе", saved("я не люблю кофе"))
    }

    @Test
    fun preferTemplate() {
        assertEquals("Пользователь предпочитает утренние прогулки", saved("я предпочитаю утренние прогулки"))
    }

    @Test
    fun livesInTemplate() {
        assertEquals("Пользователь живёт в Казани", saved("я живу в Казани"))
    }

    @Test
    fun worksInTemplate() {
        assertEquals("Пользователь работает в школе", saved("я работаю в школе"))
    }

    @Test
    fun nameTemplate() {
        assertEquals("Пользователя зовут Алексей", saved("меня зовут Алексей"))
    }

    @Test
    fun templateWorksWithoutPronoun() {
        assertEquals("Пользователь любит чай", saved("запомни: люблю чай"))
    }

    @Test
    fun nonTemplatePhraseIsKeptAsIs() {
        assertEquals(
            "У меня есть кот Барсик",
            saved("запомни что у меня есть кот Барсик"),
        )
        assertEquals(
            "Мой будильник на 7 утра",
            saved("запомни мой будильник на 7 утра"),
        )
    }

    @Test
    fun lowercaseStartIsCapitalized() {
        assertEquals("Пользователь любит чай", saved("запомни, что я люблю чай"))
    }

    // ─────────────────────────────────── отказ на секреты

    @Test
    fun passwordIsRefused() {
        assertTrue(refused("запомни мой пароль 12345"))
        assertEquals("Такое я не запоминаю.", FactExtractor.REFUSAL)
    }

    @Test
    fun codeIsRefused() {
        assertTrue(refused("запомни код 482913"))
        assertTrue(refused("запомни, что мой код 482913"))
    }

    @Test
    fun pinAndCvvAreRefused() {
        assertTrue(refused("запомни пин 8842"))
        assertTrue(refused("запомни cvv 123"))
    }

    @Test
    fun tokenAndSecretAreRefused() {
        assertTrue(refused("запомни токен для wi-fi"))
        assertTrue(refused("запомни secret word"))
        assertTrue(refused("remember that my password is 12345"))
    }

    @Test
    fun cardNumberIsRefused() {
        assertTrue(refused("запомни карту 4111 1111 1111 1111"))
    }

    @Test
    fun secretIsRefusedEvenWithoutLeadIn() {
        assertTrue(refused("мой пароль 12345"))
        assertTrue(refused("я люблю свой пароль 999"))
    }

    // ─────────────────────────────────── отрицательные кейсы

    @Test
    fun ordinaryCommandIsNotAFact() {
        assertNull(FactExtractor.decide("включи фонарик"))
        assertNull(FactExtractor.decide("позвони маме"))
        assertNull(FactExtractor.decide("какая погода"))
        assertNull(FactExtractor.decide(""))
    }

    @Test
    fun ordinarySentenceWithoutMarkersIsNotAFact() {
        assertNull(FactExtractor.decide("открой телеграм"))
        assertNull(FactExtractor.decide("сколько время"))
    }

    @Test
    fun markerWithoutLeadInStillWorks() {
        // Прежняя логика распознавания по маркерам сохранена.
        assertEquals("Пользователь любит зелёный чай", saved("я люблю зелёный чай"))
        assertEquals("Мой день рождения 15 марта", saved("мой день рождения 15 марта"))
    }

    @Test
    fun tooShortAfterCleaningIsNotSaved() {
        assertNull(FactExtractor.decide("запомни :)"))
    }

    @Test
    fun longFactIsRejected() {
        val long = "запомни " + "а".repeat(300)
        assertNull(FactExtractor.decide(long))
    }

    @Test
    fun keywordInsideAnotherWordIsNotSecret() {
        // «клюква», «кодекс» не должны давать отказ.
        assertEquals("Пользователь любит клюкву", saved("запомни что я люблю клюкву"))
    }

    /** Команды из реального прогона на телефоне: все осели в памяти как «факты». */
    @Test
    fun commandsAreNotFacts() {
        listOf(
            "домой", "поставь на паузу", "хочу зайти в telegram", "хочу тишины на телефоне",
            "напомни через две минуты", "да хочу",
        ).forEach { assertNull(it, FactExtractor.extract(it)) }
    }

    @Test
    fun stableFactsStillSaved() {
        assertNotNull(FactExtractor.extract("я люблю зелёный чай"))
        assertNotNull(FactExtractor.extract("мой день рождения в мае"))
        assertNotNull(FactExtractor.extract("запомни что хочу в отпуск летом"))
    }
}
