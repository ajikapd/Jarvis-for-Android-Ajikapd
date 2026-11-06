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

    @Test fun piecesOfOneRecordingDoNotRaiseThreshold() {
        val same = floatArrayOf(1f, 0f)
        val other = floatArrayOf(0f, 1f)
        val print = VoicePrint.enrollGroups(listOf(listOf(same, same), listOf(other, other)))
        assertEquals(0.3f, print.threshold, 0.001f)
        assertEquals(4, print.samples.size)
    }

    @Test fun windowsCoverLongSpeechWithinLimit() {
        assertEquals(0, VoiceTrim.windows(FloatArray(0)).size)
        assertEquals(1, VoiceTrim.windows(FloatArray(8000)).size)
        assertEquals(3, VoiceTrim.windows(FloatArray(48_000)).size)
        val long = VoiceTrim.windows(FloatArray(16000 * 12))
        assertTrue(long.size <= 6)
        assertTrue(long.all { it.size == 24_000 })
    }

    @Test fun shortAnswerPassesLowerMinimum() {
        val yes = FloatArray(16000).also { for (i in 0 until 2880) it[i] = 0.2f }
        assertEquals(0, VoiceTrim.speech(yes).size)
        assertEquals(2880, VoiceTrim.speech(yes, minSamples = 2400).size)
    }

    @Test fun learnedSamplesRaiseOwnerScoreWithoutChangingThreshold() {
        val print = VoicePrint.enroll(owner)
        val short = floatArrayOf(0f, 0f, 1f)
        val learned = print.with(listOf(short, short, short))
        assertEquals(print.threshold, learned.threshold, 0f)
        assertTrue(learned.score(short) > print.score(short))
    }

    @Test fun memoryKeepsOnlyNewestSamples() {
        val memory = VoiceMemory(2)
        memory.add(floatArrayOf(1f))
        memory.add(floatArrayOf(2f))
        memory.add(floatArrayOf(3f))
        assertEquals(2, memory.size)
        assertArrayEquals(floatArrayOf(2f), memory.samples[0], 0f)
    }

    @Test fun memoryRoundTripSkipsDamagedLines() {
        val memory = VoiceMemory(5)
        memory.add(floatArrayOf(1f, 2f))
        val back = VoiceMemory.decode(memory.encode() + "\noops\n3,4", 5)
        assertEquals(2, back.size)
        assertArrayEquals(floatArrayOf(3f, 4f), back.samples[1], 0f)
        assertEquals(1, VoiceMemory.decode("1\n2\n3", 1).size)
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
