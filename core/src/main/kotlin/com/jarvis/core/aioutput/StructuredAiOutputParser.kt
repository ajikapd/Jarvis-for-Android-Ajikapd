package com.jarvis.core.aioutput

/**
 * V2-05: syntax parse of LLM text → candidate JSON object.
 * Fail-closed: unknown fields at top-level rejected later by schema validator.
 */
object StructuredAiOutputParser {

    private val ALLOWED_TOP_LEVEL = setOf(
        "schemaVersion", "type", "requestId", "confidence",
        "text", "question", "reason",
        "toolId", "arguments", "goal", "intent",
        "suggestedRoute", "ambiguity", "clarify",
        "steps", "summary", "noActionReason",
    )

    fun extractJsonObject(raw: String): ParseSyntaxResult {
        val t0 = System.nanoTime()
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return ParseSyntaxResult.Fail("empty", ms(t0))
        }
        if (trimmed.length > StructuredAiLimits.MAX_JSON_CHARS) {
            return ParseSyntaxResult.Fail("oversized", ms(t0))
        }
        val jsonText = isolateJson(trimmed)
            ?: return ParseSyntaxResult.Fail("no_json", ms(t0))
        if (jsonText.length > StructuredAiLimits.MAX_JSON_CHARS) {
            return ParseSyntaxResult.Fail("oversized", ms(t0))
        }
        return try {
            val root = MiniJson.parse(jsonText)
            val obj = root.asObj() ?: return ParseSyntaxResult.Fail("not_object", ms(t0))
            if (root.nestingDepth() > StructuredAiLimits.MAX_NESTING_DEPTH) {
                return ParseSyntaxResult.Fail("too_deep", ms(t0))
            }
            ParseSyntaxResult.Ok(obj, jsonText, ms(t0))
        } catch (_: Exception) {
            ParseSyntaxResult.Fail("malformed_json", ms(t0))
        }
    }

    fun unknownTopLevelKeys(obj: JsonVal.Obj): List<String> =
        obj.keys().filter { it !in ALLOWED_TOP_LEVEL }

    /**
     * Prefer fenced ```json``` or first balanced `{...}` object.
     */
    internal fun isolateJson(text: String): String? {
        val fenced = Regex("""```(?:json)?\s*(\{[\s\S]*?})\s*```""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        if (fenced != null) return fenced.trim()

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
}

sealed class ParseSyntaxResult {
    data class Ok(
        val json: JsonVal.Obj,
        val jsonText: String,
        val parseMs: Long,
    ) : ParseSyntaxResult()

    data class Fail(val reason: String, val parseMs: Long) : ParseSyntaxResult()
}
