package com.jarvis.brain.semantic

import com.jarvis.core.runtime.RouteDecision
import com.jarvis.core.runtime.semantic.AmbiguityLevel
import com.jarvis.core.runtime.semantic.SemanticInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmStructuredSemanticInterpreterTest {

    @Test
    fun parseStructuredLine() {
        val interp = LlmStructuredSemanticInterpreter(chatLlm = { null })
        val u = interp.parse(
            "route=CHAT;goal=discuss_space;intent=chat;ambiguity=LOW;clarify=",
            SemanticInput("что думаешь про космос", "что думаешь про космос"),
        )
        assertNotNull(u)
        assertEquals(RouteDecision.CHAT, u!!.suggestedRoute)
        assertEquals("discuss_space", u.goal)
        assertTrue(u.llmInterpretationUsed)
        assertEquals(AmbiguityLevel.LOW, u.ambiguity)
    }

    @Test
    fun parsePlanRoute() {
        val interp = LlmStructuredSemanticInterpreter(chatLlm = { null })
        val u = interp.parse(
            "noise\nroute=PLAN;goal=travel;intent=plan;ambiguity=MEDIUM;clarify=куда именно?",
            SemanticInput("помоги с поездкой", "помоги с поездкой"),
        )!!
        assertEquals(RouteDecision.PLAN, u.suggestedRoute)
        assertEquals("куда именно?", u.clarifyQuestion)
    }

    @Test
    fun parseJsonSchemaV1() {
        val interp = LlmStructuredSemanticInterpreter(chatLlm = { null })
        val u = interp.parse(
            """{"schemaVersion":"1","type":"ANSWER","text":"chat","suggestedRoute":"CHAT","goal":"space","intent":"chat","ambiguity":"LOW"}""",
            SemanticInput("космос", "космос"),
        )
        assertNotNull(u)
        assertEquals(RouteDecision.CHAT, u!!.suggestedRoute)
        assertEquals("space", u.goal)
        assertTrue(u.notes.any { it.startsWith("structured_type=") })
        assertNotNull(interp.lastStructuredDiagnostics)
        assertEquals(
            com.jarvis.core.aioutput.StructuredValidationStatus.SUCCESS,
            interp.lastStructuredDiagnostics!!.validation,
        )
    }
}
