package com.jarvis.core.behavior

/**
 * Простая сериализация snapshot без внешних JSON libs (Stage 6.5 persistence).
 * Формат текстовый, только безопасные поля (без сырого текста уведомлений).
 */
object BehaviorSnapshotCodec {

    fun encode(snapshot: BehaviorStoreSnapshot): String = buildString {
        appendLine("v1")
        appendLine("disabled=${snapshot.disabledKeys.joinToString(",") { escToken(it) }}")
        appendLine("rejected=${snapshot.rejectedKeys.joinToString(",") { escToken(it) }}")
        appendLine(
            "ignore=${
                snapshot.ignoreUntil.entries.joinToString(",") {
                    "${escToken(it.key)}@${it.value}"
                }
            }",
        )
        for (c in snapshot.candidates) {
            append("C|")
            append(escField(c.patternKey)).append('|')
            append(c.kind.name).append('|')
            append(c.family.name).append('|')
            append(c.status.name).append('|')
            append(c.confidence).append('|')
            append(c.evidenceCount).append('|')
            append(c.firstObservedMillis).append('|')
            append(c.lastObservedMillis).append('|')
            append(c.uniqueDays).append('|')
            append(c.negativeEvidenceCount).append('|')
            append(escField(c.description)).append('|')
            append(escField(c.why)).append('|')
            append(c.supportingEvidenceIds.joinToString(";") { escToken(it) }).append('|')
            append(
                c.entities.entries.joinToString(";") {
                    "${escToken(it.key)}=${escToken(it.value)}"
                },
            )
            appendLine()
        }
    }

    fun decode(raw: String): BehaviorStoreSnapshot {
        if (raw.isBlank()) {
            return BehaviorStoreSnapshot(emptyList(), emptySet(), emptySet(), emptyMap())
        }
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines[0] != "v1") {
            return BehaviorStoreSnapshot(emptyList(), emptySet(), emptySet(), emptyMap())
        }
        var disabled = emptySet<String>()
        var rejected = emptySet<String>()
        var ignore = emptyMap<String, Long>()
        val candidates = mutableListOf<BehaviorCandidate>()
        for (line in lines.drop(1)) {
            when {
                line.startsWith("disabled=") ->
                    disabled = line.removePrefix("disabled=")
                        .split(',').map { unescToken(it.trim()) }.filter { it.isNotEmpty() }.toSet()
                line.startsWith("rejected=") ->
                    rejected = line.removePrefix("rejected=")
                        .split(',').map { unescToken(it.trim()) }.filter { it.isNotEmpty() }.toSet()
                line.startsWith("ignore=") -> {
                    ignore = line.removePrefix("ignore=")
                        .split(',')
                        .mapNotNull { part ->
                            val i = part.lastIndexOf('@')
                            if (i <= 0) null
                            else unescToken(part.substring(0, i)) to part.substring(i + 1).toLongOrNull()
                        }
                        .mapNotNull { (k, v) -> v?.let { k to it } }
                        .toMap()
                }
                line.startsWith("C|") -> {
                    val p = splitFields(line.removePrefix("C|"))
                    if (p.size < 14) continue
                    candidates += BehaviorCandidate(
                        patternKey = unescField(p[0]),
                        kind = BehaviorKind.valueOf(p[1]),
                        family = PatternFamily.valueOf(p[2]),
                        status = BehaviorCandidateStatus.valueOf(p[3]),
                        confidence = p[4].toFloatOrNull() ?: 0f,
                        evidenceCount = p[5].toIntOrNull() ?: 0,
                        firstObservedMillis = p[6].toLongOrNull() ?: 0L,
                        lastObservedMillis = p[7].toLongOrNull() ?: 0L,
                        uniqueDays = p[8].toIntOrNull() ?: 1,
                        negativeEvidenceCount = p[9].toIntOrNull() ?: 0,
                        description = unescField(p[10]),
                        why = unescField(p[11]),
                        supportingEvidenceIds = p[12].split(';')
                            .map { unescToken(it) }
                            .filter { it.isNotEmpty() },
                        entities = p[13].split(';').mapNotNull { e ->
                            val i = e.indexOf('=')
                            if (i <= 0) null
                            else unescToken(e.substring(0, i)) to unescToken(e.substring(i + 1))
                        }.toMap(),
                    )
                }
            }
        }
        return BehaviorStoreSnapshot(candidates, disabled, rejected, ignore)
    }

    /** Field may contain '|'; escape as \| */
    private fun escField(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n")

    private fun unescField(s: String): String = unescape(s)

    private fun escToken(s: String): String =
        s.replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("=", "\\=")
            .replace("@", "\\@")
            .replace("|", "\\|")
            .replace("\n", "\\n")

    private fun unescToken(s: String): String = unescape(s)

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    private fun splitFields(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length) {
                cur.append('\\').append(input[i + 1])
                i += 2
            } else if (input[i] == '|') {
                out += cur.toString()
                cur.clear()
                i++
            } else {
                cur.append(input[i])
                i++
            }
        }
        out += cur.toString()
        return out
    }
}
