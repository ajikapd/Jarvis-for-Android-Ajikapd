package com.jarvis.core.aioutput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Регрессия: Android-ICU не принимает незакрытый/неэкранированный `}` в regex,
 * поэтому fenced-JSON вытаскивается шаблоном с `\}`. OpenJDK такой паттерн
 * принимал и раньше, но на устройстве падал PatternSyntaxException.
 */
class StructuredAiOutputParserTest {

    @Test
    fun fencedJsonIsIsolated() {
        val raw = "```json\n{\"schemaVersion\":\"1\",\"type\":\"chat\"}\n```"
        assertEquals(
            "{\"schemaVersion\":\"1\",\"type\":\"chat\"}",
            StructuredAiOutputParser.isolateJson(raw),
        )
    }

    @Test
    fun fencedJsonWithoutLanguageIsIsolated() {
        val raw = "``` {\"type\":\"chat\"} ```"
        assertEquals("{\"type\":\"chat\"}", StructuredAiOutputParser.isolateJson(raw))
    }

    @Test
    fun plainJsonIsIsolated() {
        assertEquals("{\"a\":1}", StructuredAiOutputParser.isolateJson("text {\"a\":1} tail"))
    }

    @Test
    fun textWithoutJsonReturnsNull() {
        assertNull(StructuredAiOutputParser.isolateJson("без json"))
        assertNull(StructuredAiOutputParser.isolateJson("}"))
    }

    @Test
    fun extractJsonObjectPrefersFence() {
        val raw = "```json\n{\"schemaVersion\":\"1\"}\n```"
        val result = StructuredAiOutputParser.extractJsonObject(raw)
        assertEquals(true, result is ParseSyntaxResult.Ok)
    }
}
