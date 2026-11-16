package com.jarvis.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ThoughtStripperTest {
    private fun run(vararg chunks: String): String {
        val ts = ThoughtStripper()
        return chunks.joinToString("") { ts.strip(it) } + ts.flush()
    }

    @Test fun plainText_unchanged() = assertEquals("Просто текст", run("Просто ", "текст"))

    @Test fun bothTags_inOneChunk() = assertEquals("до после", run("до <think>скрыто</think>после"))

    @Test fun closeTag_splitBetweenChunks() = assertEquals("ответ", run("<think>скрыто</th", "ink>ответ"))

    @Test fun openTag_splitAtChunkStart() = assertEquals("ответ", run("<th", "ink>скрыто</think>ответ"))

    @Test fun openTag_splitAfterText() = assertEquals("Привет  мир", run("Привет <th", "ink>скрыто</think> мир"))

    @Test fun openTag_splitAcrossThreeChunks() = assertEquals("а б", run("а <", "thi", "nk>скрыто</think>б"))

    @Test fun thoughtTag_splitAfterText() = assertEquals("а б", run("а <though", "t>скрыто</thought>б"))

    @Test fun unclosedBlock_dropped() = assertEquals("начало ", run("начало <think>скрыто", " и дальше"))

    @Test fun falsePrefix_returnedAsText() = assertEquals("a <the слово", run("a <th", "e слово"))

    @Test fun lessThan_notLost() = assertEquals("2 < 3", run("2 <", " 3"))

    @Test fun partialTagAtEnd_returnedByFlush() = assertEquals("итог <", run("итог <"))

    @Test fun differentTags_oneAfterAnother() =
        assertEquals("ab", run("<think>x</think>a<thought>y</thought>b"))

    @Test fun byteByByte() {
        val text = "до<think>скрыто</think> после<thought>ещё</thought>!"
        assertEquals("до после!", run(*text.map { it.toString() }.toTypedArray()))
    }
}
