package com.jarvis.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSpeechGateTest {
    private fun chunk(level: Float) = FloatArray(1600) { if (it % 2 == 0) level else -level }

    private fun gate(vararg levels: Pair<Float, Int>) = WakeSpeechGate().apply {
        levels.forEach { (level, count) -> repeat(count) { onChunk(chunk(level)) } }
    }

    @Test
    fun silence_noSpeech() = assertFalse(gate(0.0003f to 40).speechPresent())

    @Test
    fun singleClick_noSpeech() =
        assertFalse(gate(0.0003f to 40, 0.05f to 1, 0.0003f to 3).speechPresent())

    @Test
    fun loudWord_speech() = assertTrue(gate(0.0003f to 40, 0.05f to 5).speechPresent())

    /** Тихое слово с 2–3 м: −47 дБ над фоном −66 дБ. */
    @Test
    fun quietDistantWord_speech() = assertTrue(gate(0.0005f to 40, 0.0045f to 5).speechPresent())

    @Test
    fun wordAtEndOfWindow_stillCounted() =
        assertTrue(gate(0.0003f to 40, 0.01f to 4, 0.0003f to 8).speechPresent())

    @Test
    fun wordOutsideWindow_forgotten() =
        assertFalse(gate(0.0003f to 40, 0.01f to 4, 0.0003f to 15).speechPresent())

    /** Постоянный шум: пол растёт и перестаёт считаться речью. */
    @Test
    fun steadyNoise_notSpeech() = assertFalse(gate(0.01f to 400).speechPresent())
}
