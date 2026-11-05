package com.jarvis.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class JarvisVoiceFxTest {
    private val sr = 22_050

    private fun tone(hz: Double, seconds: Double = 0.5, amp: Float = 0.3f) =
        FloatArray((sr * seconds).toInt()) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }

    /** Тон задавался владельцем как неизменный: частота на выходе та же. */
    @Test
    fun pitch_notShifted() {
        val out = JarvisVoiceFx.process(tone(440.0, 1.0), sr)
        val mid = out.copyOfRange(sr / 4, sr / 4 + sr / 2)
        var crossings = 0
        for (i in 1 until mid.size) if (mid[i - 1] < 0 && mid[i] >= 0) crossings++
        assertEquals(220.0, crossings.toDouble(), 3.0)
    }

    @Test
    fun rumble_removed_voice_kept() {
        val low = JarvisVoiceFx.process(tone(50.0).also { it[0] = 0.3f }, sr)
        val mid = tone(1000.0)
        val out = JarvisVoiceFx.process(mid + tone(50.0), sr)
        assertTrue(JarvisVoiceFx.rmsDb(out, 0, mid.size) > JarvisVoiceFx.rmsDb(out, mid.size, out.size - sr / 20) + 15)
        assertTrue(low.isNotEmpty())
    }

    @Test
    fun highs_cut() {
        val out = JarvisVoiceFx.process(tone(1000.0) + tone(10_000.0), sr)
        val n = sr / 2
        assertTrue(JarvisVoiceFx.rmsDb(out, 0, n) > JarvisVoiceFx.rmsDb(out, n, 2 * n) + 8)
    }

    @Test
    fun silence_staysSilent() {
        val out = JarvisVoiceFx.process(FloatArray(sr), sr)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun empty_ok() = assertEquals(0, JarvisVoiceFx.process(FloatArray(0), sr).size)

    @Test
    fun peak_normalized_noClipping_noNaN() {
        val out = JarvisVoiceFx.process(tone(300.0, amp = 0.9f), sr)
        assertTrue(out.none { it.isNaN() })
        assertEquals(0.79f, out.maxOf { abs(it) }, 0.01f)
    }

    /** Эхо не обрывается концом фразы: добавлен короткий хвост. */
    @Test
    fun tail_addedAfterSpeech() {
        val input = tone(500.0)
        val out = JarvisVoiceFx.process(input, sr)
        assertTrue(out.size > input.size)
        assertTrue(out.copyOfRange(input.size, input.size + 200).any { abs(it) > 1e-3f })
    }

    /** Реальная фраза на телефоне короткая, но обработка идёт перед звуком: важна скорость. */
    @Test
    fun fasterThanRealtime() {
        val speech = tone(200.0, seconds = 10.0)
        val t0 = System.nanoTime()
        JarvisVoiceFx.process(speech, sr)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took ${ms}ms", ms < 1_000)
    }
}
