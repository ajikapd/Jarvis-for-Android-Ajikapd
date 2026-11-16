package com.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueResponderTest {

    @Test
    fun templateGreeting_withoutChatLlm() {
        val d = DialogueResponder(null)
        val r = d.respond("привет", emptyList())
        assertTrue(r, r.contains("сэр") && r.contains("Чем могу помочь"))
    }

    @Test
    fun hush_and_goodNight_doNotReachTheModel() {
        val d = DialogueResponder(null, chatLlm = { null })
        assertTrue(d.respond("замолчи", emptyList()).contains("Умолкаю"))
        assertTrue(d.respond("спокойной ночи", emptyList()).contains("Спокойной ночи"))
        assertTrue(d.respond("какое сегодня число", emptyList()).startsWith("Сегодня"))
        assertTrue(d.respond("сколько сейчас времени", emptyList()).startsWith("Сейчас"))
    }

    @Test
    fun repeatsLastReply() {
        val d = DialogueResponder(null, chatLlm = { null })
        val r = d.respond("что ты сказал", listOf("user: погода", "assistant: Завтра дождь."))
        assertTrue(r, r.contains("Завтра дождь"))
    }

    @Test
    fun time_isDeterministicWithoutLlm() {
        val d = DialogueResponder(null, chatLlm = { null })
        val r = d.respond("который час", emptyList())
        assertTrue("expected clock-like reply, got: $r", r.matches(Regex("""Сейчас \d{1,2}:\d{2}, сэр\.""")))
    }

    @Test
    fun conversationalTime_isNotStolenByTemplate() {
        val engine = object : ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() = Unit
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ) = "Лучше уточни планы на вечер — сейчас уже поздновато."
        }
        val d = DialogueResponder(null, chatLlm = { engine })
        val detailed = d.respondDetailed(
            "как думаешь, сколько сейчас времени и стоит ли мне уже уходить?",
            emptyList(),
        )
        assertEquals(DialogueResult.Kind.LLM, detailed.kind)
        assertTrue(detailed.text.contains("поздновато") || detailed.text.contains("вечер"))
    }

    @Test
    fun freeChat_withoutModel_hintsDownload() {
        val d = DialogueResponder(null, chatLlm = { null })
        val r = d.respond("расскажи кратко про квантовую физику", emptyList())
        assertTrue(r.contains("Модели") || r.contains("Qwen") || r.contains("скачай"))
    }

    @Test
    fun freeChat_withFakeEngine_usesLlmNotTemplate() {
        val engine = object : ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() = Unit
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ) = "ANSWER: Квантовая физика описывает мир частиц."
        }
        val d = DialogueResponder(null, chatLlm = { engine })
        val r = d.respond("что думаешь про кошек", emptyList())
        // LLM-first: не шаблон «Кошки — отличные…»
        assertTrue(r.contains("Квантовая") || r.contains("частиц"))
        assertTrue(!r.startsWith("Кошки —"))
    }

    @Test
    fun freeChat_decide_parsesKind() {
        val engine = object : ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() = Unit
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ) = "Дельфины очень умные."
        }
        val d = DialogueResponder(null, chatLlm = { engine })
        val r = d.respond("расскажи про дельфинов", emptyList())
        assertTrue(r.contains("Дельфины") || r.contains("умн"))
    }

    @Test
    fun identity_and_joke_areFastPathEvenWithLlm() {
        var llmCalls = 0
        val engine = object : ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() = Unit
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ): String {
                llmCalls++
                return "LLM SHOULD NOT RUN"
            }
        }
        val d = DialogueResponder(null, chatLlm = { engine })
        val who = d.respond("кто ты", emptyList())
        assertTrue(who.contains("Джарвис") || who.contains("Jarvis"))
        val joke = d.respond("расскажи анекдот", emptyList())
        assertTrue(joke.isNotBlank())
        assertTrue(!joke.contains("LLM SHOULD NOT"))
        assertEquals(0, llmCalls)
    }

    @Test
    fun shortFollowUp_enrichesWithPreviousTopic() {
        var seen = ""
        val engine = object : ChatLlmEngine {
            override fun isReady() = true
            override fun isLoaded() = true
            override fun warm() = Unit
            override fun generate(
                userText: String,
                history: List<String>,
                memories: String,
                persona: String,
                assembled: com.jarvis.core.llm.AssembledLlmContext?,
            ): String {
                seen = userText
                return "Потому что так устроена биология."
            }
        }
        val d = DialogueResponder(null, chatLlm = { engine })
        val hist = listOf(
            "user: расскажи про дельфинов",
            "assistant: Дельфины живут в море.",
            "user: а почему они умные",
        )
        val r = d.respond("а почему они умные", hist)
        assertTrue("expected resolved topic, got: $seen", seen.contains("дельфин") || seen.contains("тем"))
        assertTrue(r.isNotBlank())
    }
}

class LlmDecisionParserTest {

    @Test
    fun parsesKindLine() {
        val d = LlmDecisionParser.parse("ANSWER: Привет, я на связи.")
        assertEquals(LlmDecisionKind.ANSWER, d!!.kind)
        assertTrue(d.utterance.contains("Привет"))
    }

    @Test
    fun parsesJsonLite() {
        val d = LlmDecisionParser.parse("""{"kind":"QUESTION","utterance":"Какой город?"}""")
        assertEquals(LlmDecisionKind.QUESTION, d!!.kind)
        assertEquals("Какой город?", d.utterance)
    }

    @Test
    fun freeTextBecomesAnswer() {
        val d = LlmDecisionParser.parse("Просто свободный ответ без метки.")
        assertEquals(LlmDecisionKind.ANSWER, d!!.kind)
        assertTrue(d.utterance.contains("свободный"))
    }
}
