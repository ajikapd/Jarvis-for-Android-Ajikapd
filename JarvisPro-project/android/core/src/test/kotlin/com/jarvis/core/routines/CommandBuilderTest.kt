package com.jarvis.core.routines

import com.jarvis.core.routines.CommandBuilder.Mode
import com.jarvis.core.routines.CommandBuilder.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBuilderTest {
    @Test fun keyIgnoresAddressAndPoliteness() {
        assertEquals("поехали", CommandBuilder.key("Джарвис, поехали!"))
        assertEquals("режим ночь", CommandBuilder.key("  Режим   ночь, сэр "))
        assertEquals("еду домой", CommandBuilder.key("Еду домой пожалуйста"))
        assertEquals("елка", CommandBuilder.key("Ёлка"))
    }

    @Test fun chainSplitsLines() {
        val r = CommandBuilder.build("Поехали", Mode.CHAIN, "включи блютуз\n- включи музыку\n\n  громкость на 50 ")
        assertEquals(Result.Ok("поехали", listOf("включи блютуз", "включи музыку", "громкость на 50")), r)
    }

    @Test fun agentBecomesDoItYourself() {
        val r = CommandBuilder.build("закажи такси", Mode.AGENT, "открой Яндекс Go и вызови такси домой") as Result.Ok
        assertEquals(listOf("сделай сам: открой Яндекс Go и вызови такси домой"), r.steps)
        assertEquals(Mode.AGENT, CommandBuilder.modeOf(r.steps))
        assertEquals("открой Яндекс Go и вызови такси домой", CommandBuilder.bodyOf(r.steps))
    }

    @Test fun rejectsBadInput() {
        assertTrue(CommandBuilder.build(" ", Mode.CHAIN, "пауза") is Result.Error)
        assertTrue(CommandBuilder.build("поехали", Mode.CHAIN, " \n ") is Result.Error)
        assertTrue(CommandBuilder.build("поехали", Mode.AGENT, "") is Result.Error)
        assertTrue(CommandBuilder.build("пауза", Mode.CHAIN, "пауза") is Result.Error)
        assertTrue(CommandBuilder.build("доброе утро", Mode.CHAIN, "скажи погоду", reserved = setOf("доброе утро")) is Result.Error)
        assertTrue(CommandBuilder.build("x", Mode.CHAIN, (1..13).joinToString("\n") { "шаг $it" }) is Result.Error)
    }
}
