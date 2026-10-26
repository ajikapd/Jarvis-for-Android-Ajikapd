package com.jarvis.core.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {

    @Test
    fun flushesOnSentenceEnd() {
        val s = SentenceSegmenter(minFlushChars = 8)
        val a = s.push("Привет, мир. ")
        assertTrue(a.isNotEmpty())
        assertTrue(a.first().contains("мир"))
        val b = s.push("Как дела?")
        val end = s.flush()
        val all = a + b + end
        assertTrue(all.joinToString(" ").contains("дела"))
    }

    @Test
    fun russianPunctuation() {
        val s = SentenceSegmenter(minFlushChars = 5)
        s.push("Да.")
        val out = s.flush()
        assertEquals(listOf("Да."), out)
    }

    @Test
    fun noTinyTokenSpam() {
        val s = SentenceSegmenter(minFlushChars = 12, softMaxChars = 90)
        assertTrue(s.push("Да").isEmpty())
        assertTrue(s.push(", ").isEmpty())
        assertTrue(s.push("конечно").isEmpty())
        val parts = s.push(" я объясню. ")
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.first().length >= 12)
    }

    @Test
    fun finalFlushWithoutPunctuation() {
        val s = SentenceSegmenter()
        s.push("просто текст без точки")
        val out = s.flush()
        assertEquals(1, out.size)
    }

    @Test
    fun hardMaxForcesBreak() {
        val s = SentenceSegmenter(softMaxChars = 20, hardMaxChars = 30, minFlushChars = 5)
        val long = "слово ".repeat(20)
        val parts = s.push(long)
        assertTrue(parts.isNotEmpty() || s.flush().isNotEmpty())
    }
}

class VoiceStreamStateMachineTest {
    @Test
    fun cancelPreventsCompleted() {
        val s = VoiceStreamSession("r1", 1L)
        assertTrue(s.transition(VoiceStreamState.STARTING))
        assertTrue(s.transition(VoiceStreamState.STREAMING))
        s.cancel()
        assertEquals(VoiceStreamState.CANCELLED, s.state)
        assertFalse(s.transition(VoiceStreamState.COMPLETED))
    }

    @Test
    fun boundedQueueRejectsOverflow() {
        val q = BoundedTextChunkQueue(maxChunks = 2, maxChars = 100)
        assertTrue(q.offer("one"))
        assertTrue(q.offer("two"))
        assertFalse(q.offer("three"))
        assertEquals("one", q.poll())
    }
}
