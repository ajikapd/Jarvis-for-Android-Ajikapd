package com.jarvis.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Б2: тексты доставки — длина, маскирование, пустой/длинный reason, три типа.
 */
class ProactiveTextBuilderTest {

    @Test
    fun suggestionHasFriendlyTemplate() {
        val text = ProactiveTextBuilder.build(
            ProactiveDecisionType.SUGGESTION,
            "ты обычно слушаешь музыку в наушниках",
        )
        assertTrue(text.voice.contains("Я заметил"))
        assertTrue(text.voice.contains("Хочешь, помогу?"))
        assertTrue(text.title.contains("предложение"))
        assertTrue(text.body.contains("Хочешь, помогу?"))
    }

    @Test
    fun questionHasQuestionTemplate() {
        val text = ProactiveTextBuilder.build(
            ProactiveDecisionType.QUESTION,
            "включить музыку",
        )
        assertTrue(text.voice.contains("Можно вопрос"))
        assertTrue(text.voice.endsWith("?"))
        assertTrue(text.title.contains("вопрос"))
    }

    @Test
    fun confirmationHasConfirmationTemplate() {
        val text = ProactiveTextBuilder.build(
            ProactiveDecisionType.CONFIRMATION_REQUEST,
            "поставить таймер",
        )
        assertTrue(text.voice.contains("Подтверди"))
        assertTrue(text.title.contains("подтверждение"))
        assertTrue(text.body.contains("Подтверждаешь?"))
    }

    @Test
    fun noOpProducesEmptyText() {
        val text = ProactiveTextBuilder.build(ProactiveDecisionType.NO_OP, "что угодно")
        assertEquals("", text.voice)
        assertEquals("", text.title)
        assertEquals("", text.body)
    }

    @Test
    fun codesAreMaskedInVoiceAndBody() {
        val text = ProactiveTextBuilder.build(
            ProactiveDecisionType.SUGGESTION,
            "код 482913 подтверждён",
        )
        assertTrue(text.voice.contains("[скрыто]"))
        assertFalse(text.voice.contains("482913"))
        assertFalse(text.body.contains("482913"))
    }

    @Test
    fun cardNumbersAreMasked() {
        val text = ProactiveTextBuilder.build(
            ProactiveDecisionType.QUESTION,
            "карта 4111 1111 1111 1111",
        )
        assertFalse(text.voice.contains("4111"))
        assertTrue(text.voice.contains("[скрыто]"))
    }

    @Test
    fun emptyReasonFallsBackToFriendlyDefault() {
        val text = ProactiveTextBuilder.build(ProactiveDecisionType.SUGGESTION, "   ")
        assertTrue(text.voice.isNotBlank())
        assertFalse(text.voice.contains("null"))
        assertFalse(text.voice.contains("  "))
    }

    @Test
    fun veryLongReasonIsClampedToVoiceLimit() {
        val longReason = "очень длинное объяснение ".repeat(40)
        val text = ProactiveTextBuilder.build(ProactiveDecisionType.SUGGESTION, longReason)
        assertTrue(text.voice.length <= ProactiveTextBuilder.MAX_VOICE_CHARS)
        assertTrue(text.voice.isNotBlank())
    }

    @Test
    fun everyTypeFitsTheVoiceLimit() {
        val longReason = "причина ".repeat(60)
        ProactiveDecisionType.values()
            .filter { it != ProactiveDecisionType.NO_OP }
            .forEach { type ->
                val text = ProactiveTextBuilder.build(type, longReason)
                assertTrue("$type voice too long", text.voice.length <= ProactiveTextBuilder.MAX_VOICE_CHARS)
            }
    }

    @Test
    fun reasonPunctuationIsNotDoubled() {
        val text = ProactiveTextBuilder.build(
            ProactiveDecisionType.SUGGESTION,
            "пора пить чай.",
        )
        assertFalse(text.voice.contains(".."))
        assertFalse(text.body.contains(".."))
    }

    @Test
    fun addressConstantIsEmptyByDefault() {
        // Позже сюда можно поставить «сэр» — шаблон сам добавит запятую.
        assertEquals("", ProactiveTextBuilder.ADDRESS)
        val text = ProactiveTextBuilder.build(ProactiveDecisionType.SUGGESTION, "чай")
        assertFalse(text.voice.startsWith(","))
    }
}
