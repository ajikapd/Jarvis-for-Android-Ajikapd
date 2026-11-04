package com.jarvis.core.cloud

class ThoughtStripper {
    private val openTags = listOf("<think>", "<thought>")
    private val closeTags = listOf("</think>", "</thought>")

    private var buffer = StringBuilder()
    private var inThought = false

    fun strip(chunk: String): String {
        buffer.append(chunk)
        val out = StringBuilder()
        var i = 0
        while (i < buffer.length) {
            if (!inThought) {
                val openIdx = openTags.map { t -> buffer.indexOf(t, i) }.filter { it >= 0 }.minOrNull()
                if (openIdx != null && openIdx == i) {
                    val tag = openTags.first { buffer.startsWith(it, i) }
                    inThought = true
                    i += tag.length
                    continue
                }
                if (openIdx != null) {
                    out.append(buffer.substring(i, openIdx))
                    i = openIdx
                    continue
                }
                // no open tag: хвост может быть началом тега, разорванного между чанками
                val tail = buffer.substring(i)
                val keep = longestSuffixPrefixOfTags(tail, openTags)
                out.append(tail, 0, tail.length - keep)
                buffer = StringBuilder(tail.substring(tail.length - keep))
                return out.toString()
            } else {
                val closeIdx = closeTags.map { t -> buffer.indexOf(t, i) }.filter { it >= 0 }.minOrNull()
                if (closeIdx != null && closeIdx == i) {
                    val tag = closeTags.first { buffer.startsWith(it, i) }
                    inThought = false
                    i += tag.length
                    continue
                }
                if (closeIdx != null) {
                    i = closeIdx
                    continue
                }
                // no close tag: хвост может содержать частичный close-тег (разрыв между чанками) —
                // оставляем самый длинный суффикс буфера, являющийся префиксом одного из closeTags
                val tail = buffer.substring(i)
                val keep = longestSuffixPrefixOfTags(tail, closeTags)
                buffer = if (keep > 0) StringBuilder(tail.substring(tail.length - keep)) else StringBuilder()
                return out.toString()
            }
        }
        buffer = StringBuilder()
        return out.toString()
    }

    fun flush(): String {
        if (inThought) { buffer = StringBuilder(); return "" }
        val out = buffer.toString()
        buffer = StringBuilder()
        return out
    }

    /** Самый длинный суффикс [s], являющийся префиксом одного из [tags] (для разорванных между чанками тегов). */
    private fun longestSuffixPrefixOfTags(s: String, tags: List<String>): Int {
        var best = 0
        for (t in tags) {
            val maxKeep = minOf(t.length - 1, s.length)
            for (keep in maxKeep downTo best + 1) {
                if (s.regionMatches(s.length - keep, t, 0, keep)) {
                    best = keep
                    break
                }
            }
        }
        return best
    }
}
