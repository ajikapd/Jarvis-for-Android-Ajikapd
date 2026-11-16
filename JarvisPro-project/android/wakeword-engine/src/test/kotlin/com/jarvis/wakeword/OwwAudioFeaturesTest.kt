package com.jarvis.wakeword

import org.junit.Assert.assertEquals
import org.junit.Test

class OwwAudioFeaturesTest {
    @Test
    fun lastStep_endsAtBufferEnd() = assertEquals(970, melWindowEnd(970, 0))

    /** Микрофон отдаёт 1600 сэмплов, и раз в 4 чтения шага два: окно не должно теряться. */
    @Test
    fun earlierStep_countsFromBufferEnd() {
        assertEquals(962, melWindowEnd(970, 1))
        assertEquals(954, melWindowEnd(970, 2))
    }
}
