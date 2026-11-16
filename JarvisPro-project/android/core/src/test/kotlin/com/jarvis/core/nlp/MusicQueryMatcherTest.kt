package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicQueryMatcherTest {

    @Test
    fun `father beats unrelated russian title`() {
        data class Track(val title: String, val artist: String)
        val library = listOf(
            Track("утекай", "тёмный принц"),
            Track("Father", "The Weeknd"),
            Track("Fantasy", "Mariah Carey"),
        )
        val best = MusicQueryMatcher.bestMatch(
            library,
            "фазер",
            { it.title to it.artist },
        )
        assertNotNull(best)
        assertEquals("Father", best!!.item.title)
        assertTrue(best.score >= MusicQueryMatcher.MIN_ACCEPT)
    }

    @Test
    fun `phonetic andy warhol matches kai angel track`() {
        data class Track(val title: String, val artist: String)
        val library = listOf(
            Track("утекай", "тёмный принц"),
            Track("andy warhol", "Kai Angel"),
        )
        val best = MusicQueryMatcher.bestMatch(
            library,
            "энди ворхол",
            { it.title to it.artist },
        )
        assertNotNull(best)
        assertEquals("andy warhol", best!!.item.title)
    }

    @Test
    fun `rejects weak unrelated match`() {
        data class Track(val title: String, val artist: String)
        val library = listOf(Track("утекай", "тёмный принц"))
        val best = MusicQueryMatcher.bestMatch(
            library,
            "bohemian rhapsody",
            { it.title to it.artist },
            minScore = MusicQueryMatcher.MIN_ACCEPT,
        )
        assertNull(best)
    }

    @Test
    fun `rejects stopword false positive the in father`() {
        data class Track(val title: String, val artist: String)
        val library = listOf(
            Track("Take The L", "dj trippie flameboy"),
            Track("утекай", "тёмный принц"),
        )
        val best = MusicQueryMatcher.bestMatch(
            library,
            "father",
            { it.title to it.artist },
            minScore = MusicQueryMatcher.MIN_SEARCH,
        )
        assertNull(best)
    }

    @Test
    fun `query variants include latin normalization`() {
        val v = MusicQueryMatcher.queryVariants("фазер")
        assertTrue(v.any { it.equals("father", ignoreCase = true) })
    }
}
