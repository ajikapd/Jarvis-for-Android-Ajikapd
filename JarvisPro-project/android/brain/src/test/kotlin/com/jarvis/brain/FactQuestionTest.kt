package com.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * А1: вопрос — не факт. Проверяется и «?» в конце, и вопросительное слово
 * первым (после срезанной вводной «запомни …»).
 */
class FactQuestionTest {

    private fun saved(text: String): String? =
        (FactExtractor.decide(text) as? FactExtractor.Decision.Save)?.text

    // ─────────────────────────────── вопросы не сохраняются

    @Test
    fun questionMarkAloneIsEnough() {
        assertNull(FactExtractor.decide("Что я люблю пить?"))
        assertNull(FactExtractor.decide("Мой день рождения 15 марта?"))
    }

    @Test
    fun questionMarkWorksWithoutSpace() {
        assertNull(FactExtractor.decide("что я люблю пить?"))
    }

    @Test
    fun russianQuestionWordsAsFirstWord() {
        assertNull(FactExtractor.decide("что я люблю пить"))
        assertNull(FactExtractor.decide("кто я"))
        assertNull(FactExtractor.decide("где я живу"))
        assertNull(FactExtractor.decide("когда я обычно встаю"))
        assertNull(FactExtractor.decide("почему я люблю кофе"))
        assertNull(FactExtractor.decide("зачем мне это"))
        assertNull(FactExtractor.decide("как меня зовут"))
    }

    @Test
    fun russianQuestionWordForms() {
        assertNull(FactExtractor.decide("какой у меня любимый чай"))
        assertNull(FactExtractor.decide("какую музыку я люблю"))
        assertNull(FactExtractor.decide("какие у меня планы"))
        assertNull(FactExtractor.decide("чей это голос"))
        assertNull(FactExtractor.decide("сколько я люблю спать"))
        assertNull(FactExtractor.decide("куда я хочу поехать"))
        assertNull(FactExtractor.decide("откуда я родом"))
        assertNull(FactExtractor.decide("люблю ли я чай"))
    }

    @Test
    fun questionAfterLeadIn() {
        assertNull(FactExtractor.decide("запомни что где я живу"))
        assertNull(FactExtractor.decide("запиши, когда я обычно встаю"))
        assertNull(FactExtractor.decide("не забудь что какой у меня любимый чай"))
    }

    @Test
    fun englishQuestions() {
        assertNull(FactExtractor.decide("what do I like to drink?"))
        assertNull(FactExtractor.decide("what do I like to drink"))
        assertNull(FactExtractor.decide("where do I live"))
        assertNull(FactExtractor.decide("how old am I"))
        assertNull(FactExtractor.decide("do I like tea"))
        assertNull(FactExtractor.decide("which tea do I prefer"))
    }

    @Test
    fun englishQuestionAfterLeadIn() {
        assertNull(FactExtractor.decide("remember that what I like is tea"))
    }

    @Test
    fun questionIsNotTreatedAsRefusal() {
        // Вопрос не сохраняем, но и «Такое я не запоминаю» на него не отвечаем.
        assertTrue(FactExtractor.decide("где мой пароль") == null)
    }

    // ─────────────────────────────── вопросительное слово НЕ первое

    @Test
    fun questionWordInsidePhraseDoesNotBlockFact() {
        assertEquals(
            "Пользователь любит когда идёт дождь",
            saved("запомни что я люблю когда идёт дождь"),
        )
    }

    @Test
    fun relativeWhereInsidePhraseDoesNotBlockFact() {
        // Вопросительное слово не первое — вопросом фраза не считается.
        // Сохраняется она по прежним правилам (маркеров факта здесь нет).
        assertEquals(false, FactExtractor.isQuestionForm("я живу там, где тепло"))
    }

    @Test
    fun ordinaryFactsStillWork() {
        assertEquals("Пользователь любит зелёный чай", saved("запомни что я люблю зелёный чай"))
        assertEquals("Мой день рождения 15 марта", saved("мой день рождения 15 марта"))
        assertEquals("Мой будильник на 7 утра", saved("запомни мой будильник на 7 утра"))
    }

    @Test
    fun englishFactStillWorks() {
        assertEquals("I like green tea", saved("remember that I like green tea"))
    }
}
