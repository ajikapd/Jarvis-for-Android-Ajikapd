package com.jarvis.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 2: чистые правила записи фактов (дедуп, seed, SECRET) и скоринг.
 */
class FactPoliciesTest {

    // ───────────────────────────────────────────── дедуп-нормализация

    @Test
    fun normalizationIgnoresCaseSpacesAndTrailingPunctuation() {
        val a = FactPolicies.normalizeForDedup("Пользователь любит зелёный чай.")
        val b = FactPolicies.normalizeForDedup("  пользователь   любит зелёный чай  ")
        assertEquals(a, b)
    }

    @Test
    fun normalizationIgnoresYoAndInnerPunctuationSpacing() {
        assertEquals(
            FactPolicies.normalizeForDedup("Пользователь живет в Москве"),
            FactPolicies.normalizeForDedup("пользователь живёт в Москве!!"),
        )
    }

    @Test
    fun differentFactsAreNotDuplicates() {
        assertTrue(
            FactPolicies.normalizeForDedup("любит чай") !=
                FactPolicies.normalizeForDedup("любит кофе"),
        )
    }

    @Test
    fun normalizationKeepsQuestionMarkInsideText() {
        // Внутренняя пунктуация значима, срезается только хвост.
        assertEquals("что? да", FactPolicies.normalizeForDedup("Что? Да."))
    }

    // ───────────────────────────────────────────── длина/валидность

    @Test
    fun storableLengthBounds() {
        assertFalse(FactPolicies.isStorable(""))
        assertFalse(FactPolicies.isStorable(" ab "))
        assertTrue(FactPolicies.isStorable("чай"))
        assertTrue(FactPolicies.isStorable("а".repeat(FactPolicies.MAX_LENGTH)))
        assertFalse(FactPolicies.isStorable("а".repeat(FactPolicies.MAX_LENGTH + 1)))
    }

    // ───────────────────────────────────────────── seed / секреты

    @Test
    fun seedFactsAreNotMigrated() {
        assertFalse(FactPolicies.shouldMigrateSource("seed"))
        assertFalse(FactPolicies.shouldMigrateSource(" SEED "))
        assertTrue(FactPolicies.shouldMigrateSource("user"))
        assertTrue(FactPolicies.shouldMigrateSource("assistant"))
        assertTrue(FactPolicies.shouldMigrateSource(null))
    }

    @Test
    fun secretsAreDetectedBeforeWrite() {
        assertTrue(FactPolicies.isSecret("мой пароль 12345"))
        assertTrue(FactPolicies.isSecret("код 482913"))
        assertTrue(FactPolicies.isSecret("карта 4111 1111 1111 1111"))
        assertFalse(FactPolicies.isSecret("Пользователь любит зелёный чай"))
        assertFalse(FactPolicies.isSecret("Пользователь живёт в Москве"))
    }

    @Test
    fun diskTextIsMasked() {
        assertEquals("код [скрыто]", FactPolicies.diskText("код 482913"))
        assertEquals(
            "Пользователь любит зелёный чай",
            FactPolicies.diskText("Пользователь любит зелёный чай"),
        )
    }

    @Test
    fun balanceIsPrivateNotSecretForFacts() {
        // PRIVATE хранить можно (это факт), SECRET — нет.
        assertEquals(com.jarvis.core.companion.Sensitivity.PRIVATE, FactPolicies.sensitivityOf("баланс 12 340 рублей"))
        assertFalse(FactPolicies.isSecret("баланс 12 340 рублей"))
    }

    // ───────────────────────────────────────────── скоринг

    @Test
    fun scoringIsShareOfQueryWordsFound() {
        // Скоринг лексический (без морфологии): слова должны совпасть буквально.
        val q = FactScoring.tokenize("любит зелёный чай")
        val hit = FactScoring.tokenize("Пользователь любит зелёный чай")
        val miss = FactScoring.tokenize("Пользователь живёт в Москве")
        assertTrue(FactScoring.score(q, hit) > 0f)
        assertEquals(0f, FactScoring.score(q, miss), 0.0001f)
    }

    @Test
    fun topNReturnsBestMatchesInOrder() {
        val facts = listOf(
            "Пользователь живёт в Москве",
            "Пользователь любит зелёный чай и пьёт его утром",
            "Пользователь любит кофе",
        )
        val top = FactScoring.topN("любит чай", facts, 2) { it }
        assertTrue(top.first().contains("чай"))
        assertEquals(2, top.size)
        assertFalse(top.any { it.contains("Москве") })
    }

    @Test
    fun topNRespectsLimitAndEmptyQuery() {
        val facts = listOf("любит чай", "любит кофе")
        assertEquals(1, FactScoring.topN("любит", facts, 1) { it }.size)
        assertTrue(FactScoring.topN("", facts, 5) { it }.isEmpty())
        assertTrue(FactScoring.topN("любит", facts, 0) { it }.isEmpty())
    }

    @Test
    fun scoringIgnoresShortWords() {
        // defaultNormalize выбрасывает слова из 1–2 символов.
        assertTrue(FactScoring.tokenize("я и он").isEmpty())
    }
}
