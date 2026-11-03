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
                // no open tag; keep partial potential tag at end
                val tail = buffer.substring(i)
                val partial = openTags.map { t -> commonPrefix(t, tail) }.maxOrNull() ?: 0
                if (partial > 0 && i + partial == buffer.length) {
                    // leave partial in buffer? We'll append rest except partial
                    out.append(buffer.substring(i, buffer.length - partial))
                    buffer = StringBuilder(buffer.substring(buffer.length - partial))
                    return out.toString()
                }
                out.append(tail)
                buffer = StringBuilder()
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
                // no close tag; drop rest
                buffer = StringBuilder()
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

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
