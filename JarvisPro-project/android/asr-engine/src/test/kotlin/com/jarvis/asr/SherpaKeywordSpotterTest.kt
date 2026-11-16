package com.jarvis.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SherpaKeywordSpotterTest {
    /** Токен не из словаря модели отключает слово молча, только в рантайме. */
    @Test
    fun keywordTokens_existInModel() {
        val tokensFile = File(
            "../app/src/main/assets/models/${AsrModelSpec.RU_STREAMING.dirName}/tokens.txt",
        )
        val known = tokensFile.readLines().map { it.substringBeforeLast(' ') }.toSet()
        val lines = SherpaKeywordSpotter.KEYWORDS.trim().lines()
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            val tokens = line.substringBefore('@').trim().split(' ')
            val unknown = tokens.filterNot { it in known }
            assertTrue("$line: неизвестные токены $unknown", unknown.isEmpty())
            assertTrue(line, line.contains('@'))
        }
    }

    @Test
    fun amplified_scalesAndClips() {
        val out = SherpaKeywordSpotter.amplified(floatArrayOf(0.01f, -0.01f, 0.5f, -0.5f))
        assertEquals(0.1f, out[0], 1e-6f)
        assertEquals(-0.1f, out[1], 1e-6f)
        assertEquals(1f, out[2], 0f)
        assertEquals(-1f, out[3], 0f)
    }
}
