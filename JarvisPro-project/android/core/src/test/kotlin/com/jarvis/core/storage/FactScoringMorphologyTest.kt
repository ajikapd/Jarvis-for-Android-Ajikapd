package com.jarvis.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * А2: лёгкая морфология в поиске фактов (без библиотек и словарей).
 */
class FactScoringMorphologyTest {

    private fun match(a: String, b: String): Boolean = FactScoring.wordsMatch(a, b)

    // ─────────────────────────────── должны совпадать

    @Test
    fun likeFormsMatch() {
        assertTrue(match("люблю", "любит"))
        assertTrue(match("люблю", "любишь"))
        assertTrue(match("любит", "любишь"))
        assertTrue(match("люблю", "любим"))
        assertTrue(match("любит", "люблю")) // симметрично
    }
    @Test
    fun teaFormsMatch() {
        assertTrue(match("чай", "чая"))
        assertTrue(match("чай", "чаю"))
        assertTrue(match("чай", "чаем"))
        assertTrue(match("чая", "чаем"))
        assertTrue(match("чаю", "чаем"))
        assertTrue(match("чая", "чаю"))
    }

    @Test
    fun liveFormsMatch() {
        assertTrue(match("живу", "живёт"))
        assertTrue(match("живёт", "живу"))
        assertTrue(match("живу", "живёшь"))
    }

    @Test
    fun coffeeMatchesItself() {
        assertTrue(match("кофе", "кофе"))
    }

    @Test
    fun musicFormsMatch() {
        assertTrue(match("музыка", "музыку"))
        assertTrue(match("музыка", "музыки"))
        assertTrue(match("музыку", "музыки"))
    }

    @Test
    fun greenFormsMatch() {
        assertTrue(match("зелёный", "зелёного"))
        assertTrue(match("зелёный", "зелёный"))
        assertTrue(match("зелёного", "зелёному"))
        assertTrue(match("зелёный", "зелёному"))
    }

    // ─────────────────────────────── НЕ должны совпадать

    @Test
    fun differentShortWordsDoNotMatch() {
        assertFalse(match("дом", "дым"))
        assertFalse(match("сон", "сын"))
        assertFalse(match("кот", "код"))
        assertFalse(match("она", "оно"))
    }

    @Test
    fun shortFourAndFiveLetterLookalikesDoNotMatch() {
        assertFalse(match("стол", "стон"))
        assertFalse(match("банк", "бант"))
        assertFalse(match("город", "горох"))
    }

    @Test
    fun differentLongWordsDoNotMatch() {
        assertFalse(match("книга", "кино"))
        assertFalse(match("мама", "мало"))
    }

    // ─────────────────────────────── ранжирование

    @Test
    fun queryFindsFactAboutTeaButNotWork() {
        val facts = listOf(
            "Пользователь любит зелёный чай",
            "Работаю в IT",
        )
        val top = FactScoring.topN("что я люблю пить", facts, 5) { it }
        assertEquals(listOf("Пользователь любит зелёный чай"), top)
    }

    @Test
    fun teaFactScoreIsPositiveAndWorkFactIsZero() {
        val q = FactScoring.tokenize("что я люблю пить")
        val tea = FactScoring.tokenize("Пользователь любит зелёный чай")
        val work = FactScoring.tokenize("Работаю в IT")
        assertTrue(FactScoring.score(q, tea) > 0f)
        assertEquals(0f, FactScoring.score(q, work), 0.0001f)
    }

    @Test
    fun exactWordsStillRankAsBefore() {
        val facts = listOf("Пользователь любит зелёный чай", "Пользователь живёт в Москве")
        val top = FactScoring.topN("зелёный чай", facts, 1) { it }
        assertEquals(listOf("Пользователь любит зелёный чай"), top)
    }

    @Test
    fun emptyInputsAreSafe() {
        assertFalse(match("", "чай"))
        assertFalse(match("чай", ""))
        assertEquals(0f, FactScoring.score(emptyList(), listOf("чай")), 0.0001f)
    }
}
