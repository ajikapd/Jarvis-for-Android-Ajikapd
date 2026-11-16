package com.jarvis.tools

import com.jarvis.tools.SoundCloudScreen.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundCloudScreenTest {
    @Test fun skipsProfileAndTakesTrack() {
        val rows = listOf(
            Row("Imagine Dragons", 200, 250),
            Row("5,6M Followers", 260, 300),
            Row("8,6M · 3:39", 700, 750),
            Row("21,5M · 3:25", 1300, 1350),
        )
        assertEquals(700, SoundCloudScreen.firstTrack(rows)?.top)
    }

    @Test fun titleWithTimeInsideIsNotDuration() =
        assertNull(SoundCloudScreen.firstTrack(listOf(Row("22:30 at night", 1, 2))))

    @Test fun longMix() = assertEquals(5, SoundCloudScreen.firstTrack(listOf(Row("1:02:33", 5, 6)))?.top)

    private val results = listOf(
        Row("Queen", 200, 250),
        Row("Bohemian Rhapsody (Nightcore Remix)", 600, 650),
        Row("1,2M · 3:40", 660, 700),
        Row("Queen", 900, 950),
        Row("Bohemian Rhapsody", 960, 1000),
        Row("8,6M · 5:55", 1010, 1050),
        Row("Queen", 1300, 1350),
        Row("Radio Ga Ga", 1360, 1400),
        Row("2M · 5:43", 1410, 1450),
    )

    @Test fun skipsRemixNobodyAskedFor() =
        assertEquals(1010, SoundCloudScreen.bestTrack(results, "queen bohemian rhapsody")?.top)

    @Test fun keepsRemixWhenAsked() =
        assertEquals(660, SoundCloudScreen.bestTrack(results, "bohemian rhapsody remix")?.top)

    @Test fun picksByTitleNotPosition() =
        assertEquals(1410, SoundCloudScreen.bestTrack(results, "radio ga ga")?.top)

    @Test fun noMatchTrustsSoundCloudOrder() =
        assertEquals(660, SoundCloudScreen.bestTrack(results, "мой любимый трек")?.top)

    @Test fun blankQueryTakesFirst() = assertEquals(660, SoundCloudScreen.bestTrack(results, " ")?.top)

    @Test fun noTracksNoPick() = assertNull(SoundCloudScreen.bestTrack(listOf(Row("Queen", 1, 2)), "queen"))
}
