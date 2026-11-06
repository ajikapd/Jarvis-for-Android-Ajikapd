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
}
