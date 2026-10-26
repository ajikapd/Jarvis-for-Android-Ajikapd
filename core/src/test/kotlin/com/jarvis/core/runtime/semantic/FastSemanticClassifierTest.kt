package com.jarvis.core.runtime.semantic

import com.jarvis.core.runtime.RouteDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastSemanticClassifierTest {

    private val c = FastSemanticClassifier()

    private fun input(text: String, lastTool: String? = null, prev: String? = null) =
        SemanticInput(
            rawText = text,
            normalizedText = text.lowercase(),
            previousUserText = prev,
            lastToolId = lastTool,
        )

    @Test
    fun volumeParaphrasesMapToSameIntent() {
        val phrases = listOf(
            "сделай громче",
            "прибавь звук",
            "что-то тихо",
            "давай погромче",
            "сделай здесь потише",
        )
        for (p in phrases) {
            val u = c.interpret(input(p))
            assertNotNull(p, u)
            assertEquals(p, "volume", u!!.intent)
            assertEquals(p, RouteDecision.FAST_SKILL, SemanticRouteSelector.select(u))
        }
    }

    @Test
    fun flashlightObviousFastSkill() {
        val u = c.interpret(input("включи фонарик"))!!
        assertEquals("flashlight", u.intent)
        assertEquals(RouteDecision.FAST_SKILL, SemanticRouteSelector.select(u))
    }

    @Test
    fun greetingIsTemplate() {
        val u = c.interpret(input("привет"))!!
        assertEquals(RouteDecision.TEMPLATE, SemanticRouteSelector.select(u))
    }

    @Test
    fun timeIsTemplateButConversationalTimeIsChat() {
        val short = c.interpret(input("который час"))!!
        assertEquals(RouteDecision.TEMPLATE, SemanticRouteSelector.select(short))

        val conversational = c.interpret(
            input("как думаешь, сколько сейчас времени и стоит ли мне уже уходить?"),
        )!!
        assertEquals(RouteDecision.CHAT, SemanticRouteSelector.select(conversational))
    }

    @Test
    fun openConversationIsChat() {
        val u = c.interpret(input("что ты думаешь о космосе?"))!!
        assertEquals(RouteDecision.CHAT, SemanticRouteSelector.select(u))
        assertEquals("chat", u.intent)
    }

    @Test
    fun freeFormMultiStepIsPlan() {
        val u = c.interpret(
            input("открой браузер и найди мне самые дешёвые билеты в Берлин на завтра"),
        )!!
        assertEquals(RouteDecision.PLAN, SemanticRouteSelector.select(u))
        assertTrue(u.requiredCapabilities.isNotEmpty())
    }

    @Test
    fun ambiguousPronounWithoutContextClarifies() {
        val u = c.interpret(input("открой его"))!!
        assertEquals(RouteDecision.CLARIFY, SemanticRouteSelector.select(u))
        assertNotNull(u.clarifyQuestion)
    }

    @Test
    fun ambiguousPronounIgnoresUnrelatedPreviousUtterance() {
        val u = c.interpret(input("открой его", prev = "привет"))!!
        assertEquals(RouteDecision.CLARIFY, SemanticRouteSelector.select(u))
    }

    @Test
    fun ambiguousPronounDefersWhenPriorAppKnown() {
        assertNull(c.interpret(input("открой его", prev = "открой Telegram")))
        assertNull(c.interpret(input("открой его", lastTool = "app.launch")))
    }

    @Test
    fun incompleteActionClarifiesWithCandidates() {
        val u = c.interpret(input("включи"))!!
        assertEquals(RouteDecision.CLARIFY, SemanticRouteSelector.select(u))
        assertTrue(u.clarifyQuestion!!.contains("фонарик") || u.candidateInterpretations.isNotEmpty())
    }

    @Test
    fun launcherFastSkill() {
        val u = c.interpret(input("открой Telegram"))!!
        assertEquals(RouteDecision.FAST_SKILL, SemanticRouteSelector.select(u))
        assertEquals("launcher", u.intent)
    }

    @Test
    fun unknownFallsThroughNull() {
        // random short non-matching → null so BrainRouter can try
        assertNull(c.interpret(input("xyzzy plugh")))
    }
}

class SemanticRouteSelectorTest {
    @Test
    fun highAmbiguityForcesClarify() {
        val u = SemanticUnderstanding(
            rawInput = "x",
            normalizedInput = "x",
            goal = "g",
            intent = "i",
            confidence = 0.9f,
            ambiguity = AmbiguityLevel.HIGH,
            clarity = SemanticClarity.CLEAR,
            suggestedRoute = RouteDecision.FAST_SKILL,
            routeReason = "t",
            clarifyQuestion = "?",
            requiredCapabilities = listOf("volume"),
        )
        assertEquals(RouteDecision.CLARIFY, SemanticRouteSelector.select(u))
    }
}
