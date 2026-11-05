package com.jarvis.core.aioutput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudToolActionTest {
    private fun parse(text: String) = CloudToolAction.parse(text, "r1")

    @Test
    fun parsesArgsWithTypes() {
        val c = parse("""ACT {"tool":"timer","args":{"minutes":2,"action":"x"}}""")!!
        assertEquals("timer", c.toolId)
        assertEquals(2, c.arguments["minutes"])
        assertEquals("x", c.arguments["action"])
    }

    @Test
    fun noArgs_ok() = assertEquals("screenshot", parse("""ACT {"tool":"screenshot","args":{}}""")?.toolId)

    @Test
    fun missingArgsObject_ok() = assertEquals("screenshot", parse("""ACT {"tool":"screenshot"}""")?.toolId)

    @Test
    fun plainText_notAction() = assertNull(parse("Столица Франции — Париж."))

    /** Экранные инструменты закрыты: модель может попасть под текст с экрана или из уведомления. */
    @Test
    fun unlistedTool_rejected() {
        assertNull(parse("""ACT {"tool":"ui.click","args":{"text":"Удалить"}}"""))
        assertNull(parse("""ACT {"tool":"shell","args":{}}"""))
    }

    @Test
    fun brokenJson_rejectedButStillAction() {
        val text = """ACT {"tool":"timer","""
        assertNull(parse(text))
        assertTrue(CloudToolAction.isAction(text))
    }

    @Test
    fun holdsPrefixOnly() {
        assertTrue(CloudToolAction.mayBeAction("A"))
        assertTrue(CloudToolAction.mayBeAction("AC"))
        assertTrue(CloudToolAction.mayBeAction("ACT {"))
        assertFalse(CloudToolAction.mayBeAction("Да"))
        assertFalse(CloudToolAction.mayBeAction("Apple — компания"))
    }

    @Test
    fun wordStartingWithMarker_isNotAction() = assertFalse(CloudToolAction.isAction("ACTION movie"))
}
