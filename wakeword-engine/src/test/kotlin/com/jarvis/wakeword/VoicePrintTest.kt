package com.jarvis.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoicePrintTest {
    private val owner = listOf(
        floatArrayOf(1f, 0.1f, 0f),
        floatArrayOf(1f, 0f, 0.1f),
        floatArrayOf(0.9f, 0.1f, 0.1f),
        floatArrayOf(1f, 0.2f, 0f),
        floatArrayOf(1f, 0f, 0.2f),
        floatArrayOf(0.9f, 0f, 0f),
    )

    @Test fun ownerPassesAndStrangerDoesNot() {
        val print = VoicePrint.enroll(owner)
        assertTrue(print.accepts(floatArrayOf(1f, 0.05f, 0.05f)))
        assertFalse(print.accepts(floatArrayOf(0f, 1f, 0f)))
        assertFalse(print.accepts(floatArrayOf(-1f, 0f, 0f)))
    }

    @Test fun thresholdStaysInSafeRange() {
        assertEquals(0.5f, VoicePrint.enroll(owner).threshold, 0.001f)
        val scattered = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(-1f, 0f))
        assertEquals(0.3f, VoicePrint.enroll(scattered).threshold, 0.001f)
    }

    @Test fun oneBadRecordingDoesNotBlockOwner() {
        val print = VoicePrint(owner.dropLast(1) + floatArrayOf(0f, 1f, 0f), 0.5f)
        assertTrue(print.accepts(floatArrayOf(1f, 0f, 0f)))
    }

    @Test fun encodeRoundTrip() {
        val print = VoicePrint.enroll(owner)
        val back = assertNotNullValue(VoicePrint.decode(print.encode()))
        assertEquals(print.threshold, back.threshold, 0f)
        assertEquals(owner.size, back.samples.size)
        assertArrayEquals(owner[2], back.samples[2], 0f)
    }

    @Test fun damagedFileGivesNoPrint() {
        assertNull(VoicePrint.decode(""))
        assertNull(VoicePrint.decode("oops\n1,2\n3,4"))
        assertNull(VoicePrint.decode("0.4\n1,2"))
        assertNull(VoicePrint.decode("0.4\n1,2\n1,2,3"))
    }

    @Test fun cosineHandlesBadInput() {
        assertEquals(0f, VoicePrint.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
        assertEquals(0f, VoicePrint.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 0f)
    }

    @Test fun recentAudioKeepsTheLastSamplesInOrder() {
        val ring = RecentAudio(4)
        ring.push(floatArrayOf(1f, 2f, 3f))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), ring.snapshot(), 0f)
        ring.push(floatArrayOf(4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), ring.snapshot(), 0f)
        ring.clear()
        assertEquals(0, ring.snapshot().size)
    }

    @Test fun trimKeepsSpeechAndDropsSilence() {
        val tone = FloatArray(8000) { (0.1 * sin(2 * PI * 200 * it / 16000)).toFloat() }
        val pcm = FloatArray(8000) + tone + FloatArray(8000)
        val out = VoiceTrim.speech(pcm)
        assertTrue(out.size in 7000..9000)
    }

    @Test fun trimRefusesTooLittleSpeech() {
        assertEquals(0, VoiceTrim.speech(FloatArray(16000)).size)
        val blip = FloatArray(16000).also { for (i in 0 until 1600) it[i] = 0.2f }
        assertEquals(0, VoiceTrim.speech(blip).size)
        assertEquals(0, VoiceTrim.speech(FloatArray(10)).size)
    }

    private fun assertNotNullValue(print: VoicePrint?): VoicePrint {
        assertNotNull(print)
        return print!!
    }
}
