package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuNumbersTest {
    private fun at(text: String): Int? = text.split(" ").let { RuNumbers.endingAt(it, it.lastIndex) }

    @Test fun digits() = assertEquals(40, at("на 40"))
    @Test fun unit() = assertEquals(2, at("на две"))
    @Test fun teen() = assertEquals(15, at("на пятнадцать"))
    @Test fun tens() = assertEquals(50, at("на пятьдесят"))
    @Test fun compound() = assertEquals(25, at("на двадцать пять"))
    @Test fun hundreds() = assertEquals(125, at("сто двадцать пять"))
    @Test fun notNumber() = assertNull(at("на столе"))
    /** «пять двадцать» — не 25: порядок разрядов нарушен. */
    @Test fun wrongOrderTakesLast() = assertEquals(20, at("пять двадцать"))

    @Test fun beforeUnit() =
        assertEquals(10, RuNumbers.before("таймер на десять секунд".split(" "), setOf("секунд")))
}
