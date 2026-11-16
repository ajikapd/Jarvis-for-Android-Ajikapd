package com.jarvis.core.aioutput

import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.execution.CatalogToolRegistry
import com.jarvis.core.execution.ToolExecutionLocality
import com.jarvis.core.execution.ToolProviderSource
import com.jarvis.core.execution.ToolRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredAiOutputPipelineTest {

    private val registry = CatalogToolRegistry().also { cat ->
        cat.register(
            ToolRegistration(
                definition = ToolDefinition(
                    id = "volume",
                    name = "Volume",
                    description = "Volume control",
                    parameters = listOf(
                        ToolParameter("action", "up/down", ParameterType.STRING, required = true),
                    ),
                    riskLevel = RiskLevel.LOW,
                ),
                source = ToolProviderSource.NATIVE,
                locality = ToolExecutionLocality.LOCAL,
            ),
        )
        cat.register(
            ToolRegistration(
                definition = ToolDefinition(
                    id = "flashlight",
                    name = "Flashlight",
                    description = "Torch",
                    parameters = listOf(
                        ToolParameter("action", "on/off", ParameterType.STRING, required = true),
                    ),
                    riskLevel = RiskLevel.SAFE,
                ),
                source = ToolProviderSource.NATIVE,
                locality = ToolExecutionLocality.LOCAL,
            ),
        )
    }

    private val pipeline = StructuredAiOutputPipeline(registry)

    @Test
    fun validAnswer() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"ANSWER","text":"Небо голубое из-за рассеяния."}""",
            StructuredAiTask.CHAT,
        )
        assertTrue(r is StructuredAiProcessResult.Valid)
        val v = r as StructuredAiProcessResult.Valid
        assertEquals(StructuredAiResponseType.ANSWER, v.envelope.responseType)
        assertEquals(StructuredValidationStatus.SUCCESS, v.diagnostics.validation)
    }

    @Test
    fun validClarification() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"CLARIFICATION","question":"Музыку или звонок?","reason":"AMBIGUOUS_TARGET"}""",
            StructuredAiTask.SEMANTIC_INTERPRETATION,
        )
        assertTrue(r is StructuredAiProcessResult.Valid)
    }

    @Test
    fun validToolRequest() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"volume","arguments":{"action":"up"},"goal":"louder"}""",
            StructuredAiTask.TOOL_REQUEST,
        )
        assertTrue(r is StructuredAiProcessResult.Valid)
        val action = StructuredAiAdapters.toProposedAction((r as StructuredAiProcessResult.Valid).envelope)
        assertNotNull(action)
        assertEquals("volume", action!!.toolId)
        assertEquals("up", action.arguments["action"])
    }

    @Test
    fun validPlan() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"PLAN","goal":"brightness","steps":[{"stepId":"s1","kind":"TOOL","toolId":"flashlight","parameters":{"action":"on"}}]}""",
            StructuredAiTask.PLAN,
        )
        assertTrue(r is StructuredAiProcessResult.Valid)
        val plan = StructuredAiAdapters.toActionPlanDraft((r as StructuredAiProcessResult.Valid).envelope)
        assertNotNull(plan)
        assertEquals(1, plan!!.steps.size)
    }

    @Test
    fun validNoAction() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"NO_ACTION","noActionReason":"nothing_to_do"}""",
            StructuredAiTask.SEMANTIC_INTERPRETATION,
        )
        assertTrue(r is StructuredAiProcessResult.Valid)
    }

    @Test
    fun validConfirmationRequired() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"CONFIRMATION_REQUIRED","summary":"Удалить файл?","toolId":"volume","arguments":{"action":"up"}}""",
            StructuredAiTask.TOOL_REQUEST,
        )
        assertTrue(r is StructuredAiProcessResult.Valid)
        // LLM suggestion ≠ policy ALLOW
        assertEquals(
            StructuredAiResponseType.CONFIRMATION_REQUIRED,
            (r as StructuredAiProcessResult.Valid).envelope.responseType,
        )
    }

    @Test
    fun malformedJsonRejected() {
        val r = pipeline.process("{type: ANSWER", StructuredAiTask.TOOL_REQUEST)
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertEquals(StructuredParseStatus.FAIL, (r as StructuredAiProcessResult.Invalid).diagnostics.parse)
    }

    @Test
    fun truncatedJsonRejected() {
        val r = pipeline.process("""{"schemaVersion":"1","type":"ANSWER","text":"hi""", StructuredAiTask.CHAT)
        assertTrue(r is StructuredAiProcessResult.Invalid)
    }

    @Test
    fun unknownTypeRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"HACK","text":"x"}""",
            StructuredAiTask.CHAT,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
    }

    @Test
    fun unknownFieldsRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"ANSWER","text":"ok","autonomy":"ALLOW"}""",
            StructuredAiTask.CHAT,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertTrue((r as StructuredAiProcessResult.Invalid).diagnostics.validationReason!!.contains("unknown"))
    }

    @Test
    fun unknownToolRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"not_a_real_tool","arguments":{}}""",
            StructuredAiTask.TOOL_REQUEST,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertTrue((r as StructuredAiProcessResult.Invalid).diagnostics.validationReason!!.contains("INVALID_TOOL"))
    }

    @Test
    fun missingRequiredArgRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"volume","arguments":{}}""",
            StructuredAiTask.TOOL_REQUEST,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertTrue((r as StructuredAiProcessResult.Invalid).diagnostics.validationReason!!.contains("missing_required"))
    }

    @Test
    fun forbiddenArgRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"volume","arguments":{"action":"up","allow":"true"}}""",
            StructuredAiTask.TOOL_REQUEST,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertTrue((r as StructuredAiProcessResult.Invalid).diagnostics.validationReason!!.contains("forbidden"))
    }

    @Test
    fun emptyAnswerRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"ANSWER","text":"   "}""",
            StructuredAiTask.CHAT,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
    }

    @Test
    fun fakePermissionFieldRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"volume","arguments":{"action":"up"},"permission":"GRANTED"}""",
            StructuredAiTask.TOOL_REQUEST,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
    }

    @Test
    fun repairOnceThenSuccess() {
        var repairs = 0
        val r = pipeline.processWithRepair(
            raw = "not json at all",
            task = StructuredAiTask.CHAT,
            repairFn = { _, _ ->
                repairs++
                """{"schemaVersion":"1","type":"ANSWER","text":"Починено."}"""
            },
        )
        assertEquals(1, repairs)
        assertTrue(r is StructuredAiProcessResult.Valid)
        assertTrue((r as StructuredAiProcessResult.Valid).diagnostics.repairAttempted)
        assertEquals("SUCCESS", r.diagnostics.repairResult)
    }

    @Test
    fun repairOnceThenStillInvalid_noExecute() {
        val r = pipeline.processWithRepair(
            raw = "{broken",
            task = StructuredAiTask.TOOL_REQUEST,
            repairFn = { _, _ -> """{"schemaVersion":"1","type":"TOOL_REQUEST","toolId":"ghost","arguments":{}}""" },
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertEquals("FAIL", (r as StructuredAiProcessResult.Invalid).diagnostics.repairResult)
        // Invalid structured output must not become a ProposedAction via adapters on Valid path only
        assertTrue(r.diagnostics.validation == StructuredValidationStatus.FAIL ||
            r.diagnostics.parse == StructuredParseStatus.FAIL)
    }

    @Test
    fun unsupportedSchemaRejected() {
        val r = pipeline.process(
            """{"schemaVersion":"99","type":"ANSWER","text":"x"}""",
            StructuredAiTask.CHAT,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
        assertTrue((r as StructuredAiProcessResult.Invalid).diagnostics.validationReason!!.contains("unsupported_schema"))
    }

    @Test
    fun noActionMustNotCarryTool() {
        val r = pipeline.process(
            """{"schemaVersion":"1","type":"NO_ACTION","toolId":"volume"}""",
            StructuredAiTask.SEMANTIC_INTERPRETATION,
        )
        assertTrue(r is StructuredAiProcessResult.Invalid)
    }
}
