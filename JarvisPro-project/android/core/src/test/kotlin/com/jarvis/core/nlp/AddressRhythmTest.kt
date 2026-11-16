package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AddressRhythmTest {
    @Test fun stripsEveryForm() {
        assertEquals("Фонарик включён.", AddressRhythm.strip("Фонарик включён, сэр."))
        assertEquals("Да?", AddressRhythm.strip("Да, сэр?"))
        assertEquals("Внимание: мало заряда.", AddressRhythm.strip("Внимание, сэр: мало заряда."))
        assertEquals("Как скажете, всё готово.", AddressRhythm.strip("Как скажете, сэр, всё готово."))
        assertEquals("Разрешите доложить.", AddressRhythm.strip("Сэр, разрешите доложить."))
    }

    @Test fun otherWordsStay() {
        assertEquals("Играет Сэра и другие.", AddressRhythm.strip("Играет Сэра и другие."))
    }

    @Test fun loneAddressIsNotEmptied() = assertEquals("Сэр.", AddressRhythm.strip("Сэр."))

    @Test fun neverTwiceInARowAndNotTooRare() {
        val rhythm = AddressRhythm(Random(7))
        val kept = List(600) {
            rhythm.nextReply()
            rhythm.apply("Готово, сэр.").contains("сэр")
        }
        assertTrue(kept.zipWithNext().none { (a, b) -> a && b })
        var gap = 0
        kept.forEach { hasAddress ->
            gap = if (hasAddress) 0 else gap + 1
            assertTrue(gap <= 3)
        }
        val share = kept.count { it } / 600.0
        assertTrue("$share", share in 0.25..0.45)
    }

    @Test fun oneReplyKeepsOneDecision() {
        val rhythm = AddressRhythm(Random(1))
        rhythm.nextReply()
        assertEquals(rhythm.apply("Раз, сэр."), "Раз, сэр.")
        assertEquals(rhythm.apply("Два, сэр."), "Два, сэр.")
    }
}
