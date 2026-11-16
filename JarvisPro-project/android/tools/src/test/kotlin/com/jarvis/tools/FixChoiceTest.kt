package com.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixChoiceTest {
    private val now = 10_000_000L

    @Test fun picksMostAccurateFresh() {
        val best = FixChoice.best(
            listOf(Fix(1.0, 1.0, now - 60_000, 900f), Fix(2.0, 2.0, now - 120_000, 30f)),
            now,
        )
        assertEquals(2.0, best!!.lat, 0.0)
    }

    @Test fun ignoresStale() = assertNull(FixChoice.best(listOf(Fix(1.0, 1.0, now - 5 * 3_600_000L, 10f)), now))

    @Test fun newerWinsOnTie() =
        assertEquals(3.0, FixChoice.best(listOf(Fix(1.0, 1.0, now - 900, 50f), Fix(3.0, 3.0, now - 100, 50f)), now)!!.lat, 0.0)

    @Test fun placeNameFallsBack() {
        assertEquals("Донецк", FixChoice.placeName("Донецк", "Донецкий", "ДНР"))
        assertEquals("Пушкинский район", FixChoice.placeName(" ", "Пушкинский район", "Московская область"))
        assertNull(FixChoice.placeName(null, null, null))
    }
}
