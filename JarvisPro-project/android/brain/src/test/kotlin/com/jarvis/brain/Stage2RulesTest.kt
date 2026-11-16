package com.jarvis.brain

import com.jarvis.core.common.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage2RulesTest {
    private val brain = RuleBasedBrain()

    private fun type(text: String): IntentType {
        val r = brain.route(
            com.jarvis.core.common.model.UserRequest(text),
            emptyList(),
            emptyList(),
        )
        return (r as BrainResult.SkillCall).intent.type
    }

    @Test
    fun weather() {
        assertEquals(IntentType.WEATHER, type("какая погода в Москве"))
        assertEquals(IntentType.WEATHER, type("погода"))
    }

    @Test
    fun music() {
        assertEquals(IntentType.MUSIC, type("включи песню father"))
        assertEquals(IntentType.MUSIC, type("поставь на паузу"))
        assertEquals(IntentType.MUSIC, type("следующий трек"))
        assertEquals(IntentType.MUSIC, type("продолжить воспроизведение"))
        assertEquals(IntentType.MUSIC, type("включи избранное"))
        assertEquals(IntentType.MUSIC, type("включи скачанное"))
    }

    @Test
    fun knowledge() {
        assertEquals(IntentType.KNOWLEDGE, type("кто такой Пушкин"))
        assertEquals(IntentType.KNOWLEDGE, type("кто такие дельфины"))
        assertEquals(IntentType.KNOWLEDGE, type("расскажи про Марс"))
        assertEquals(IntentType.KNOWLEDGE, type("загугли кто такой Пушкин"))
        assertEquals(IntentType.KNOWLEDGE, type("расскажи коротко про дельфинов"))
        assertEquals(IntentType.KNOWLEDGE, type("коротко про кошек"))
        val dolphin = brain.route(
            com.jarvis.core.common.model.UserRequest("расскажи коротко про дельфинов"),
            emptyList(),
            emptyList(),
        ) as BrainResult.SkillCall
        assertEquals("дельфинов", dolphin.intent.slots["query"]?.value)
    }

    @Test
    fun explainGoesToKnowledge() {
        val r = brain.route(
            com.jarvis.core.common.model.UserRequest("что такое фотосинтез"),
            emptyList(),
            emptyList(),
        )
        assertTrue(r is BrainResult.SkillCall)
        assertEquals(IntentType.KNOWLEDGE, (r as BrainResult.SkillCall).intent.type)
    }

    @Test
    fun musicPlayHasQuery() {
        val r = brain.route(
            com.jarvis.core.common.model.UserRequest("включи песню Bohemian Rhapsody"),
            emptyList(),
            emptyList(),
        ) as BrainResult.SkillCall
        assertEquals("play", r.intent.slots["action"]?.value)
        assertTrue(r.intent.slots["query"]?.value.toString().contains("bohemian", ignoreCase = true))
    }

    @Test
    fun musicFavoritesScope() {
        val r = brain.route(
            com.jarvis.core.common.model.UserRequest("включи избранное"),
            emptyList(),
            emptyList(),
        ) as BrainResult.SkillCall
        assertEquals("favorites", r.intent.slots["scope"]?.value)
    }

    @Test
    fun musicResumePlayback() {
        val r = brain.route(
            com.jarvis.core.common.model.UserRequest("продолжить воспроизведение"),
            emptyList(),
            emptyList(),
        ) as BrainResult.SkillCall
        assertEquals("resume", r.intent.slots["action"]?.value)
    }
}
