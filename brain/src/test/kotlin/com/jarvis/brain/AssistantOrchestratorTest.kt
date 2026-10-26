package com.jarvis.brain

import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.RiskBasedPolicy
import com.jarvis.core.common.Tool
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.ConfidenceLevel
import com.jarvis.core.common.model.ConversationContext
import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.Slot
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.skills.SkillManifestRegistry
import com.jarvis.core.skills.SkillRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOrchestratorTest {

    private class FakeFlashlightTool(private val risk: RiskLevel = RiskLevel.SAFE) : Tool {
        override val definition = ToolDefinition(
            id = "flashlight",
            name = "Фонарик",
            description = "Тестовая реализация",
            parameters = listOf(
                ToolParameter("action", "on/off", ParameterType.ENUM, required = true, allowedValues = listOf("on", "off"))
            ),
            riskLevel = risk,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY
        )

        override fun execute(call: ToolCall): ToolResult {
            val wantOn = call.arguments["action"] == "on"
            return ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.SUCCESS,
                message = if (wantOn) "Фонарик включён." else "Фонарик выключен.",
                data = mapOf("on" to wantOn)
            )
        }
    }

    /** Роутер, который «кто ты» → Chat, остальное → фонарик. */
    private class TestRouter : BrainRouter {
        override fun route(
            request: UserRequest,
            history: List<String>,
            topSkills: List<BrainRouter.SkillInfo>
        ): BrainResult {
            val t = request.text.lowercase().trim()
            return if (t.contains("кто ты") || t.contains("привет") || t.contains("расскажи анекдот")) {
                BrainResult.Chat(confidence = 0.9f)
            } else {
                BrainResult.SkillCall(
                    intent = Intent(
                        type = IntentType.FLASHLIGHT,
                        normalizedText = request.text,
                        slots = mapOf("action" to Slot("action", "on")),
                        confidence = 0.95f,
                        confidenceLevel = ConfidenceLevel.HIGH
                    ),
                    confidence = 0.95f
                )
            }
        }
    }

    private fun orchestrator(tool: Tool): AssistantOrchestrator {
        val registry = ToolRegistry().apply { register(tool) }
        return AssistantOrchestrator(
            brainRouter = TestRouter(),
            skillRetriever = SkillRetriever(SkillManifestRegistry(), k = 6),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            toolPolicy = RiskBasedPolicy(),
            context = ConversationContext(),
            longTermMemory = FakeMemory(),
            dialogue = FakeDialogue()
        )
    }

    @Test
    fun `known flashlight request produces PROCESSED response`() {
        val response = orchestrator(FakeFlashlightTool()).process(UserRequest("включи фонарик"))

        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.PROCESSED, response.status)
        assertEquals("Фонарик включён.", response.replyText)
        assertEquals(com.jarvis.core.common.model.ToolResultStatus.SUCCESS, response.toolResult?.status)
        assertEquals("FAST_SKILL", response.route)
        assertNotNull(response.verification)
    }

    @Test
    fun `chat routing goes to dialogue responder`() {
        val orch = orchestrator(FakeFlashlightTool())
        val response = orch.process(UserRequest("кто ты"))

        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.PROCESSED, response.status)
        assertEquals("hello-from-fake", response.replyText)
        assertEquals("TEMPLATE", response.route)
        assertEquals(response.requestId, orch.lastTrace?.requestId)
    }

    @Test
    fun `blocked tool is denied by policy`() {
        val response = orchestrator(FakeFlashlightTool(RiskLevel.BLOCKED)).process(UserRequest("включи фонарик"))

        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.DENIED, response.status)
    }

    @Test
    fun `confirm-risk tool requests confirmation`() {
        val response = orchestrator(FakeFlashlightTool(RiskLevel.CONFIRM)).process(UserRequest("включи фонарик"))

        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.NEEDS_CONFIRMATION, response.status)
        assertTrue(response.replyText.contains("да", ignoreCase = true))
    }

    @Test
    fun `confirm-risk tool executes after yes`() {
        val context = ConversationContext()
        val registry = ToolRegistry().apply { register(FakeFlashlightTool(RiskLevel.CONFIRM)) }
        val brain = AssistantOrchestrator(
            brainRouter = TestRouter(),
            skillRetriever = SkillRetriever(SkillManifestRegistry(), k = 6),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            toolPolicy = RiskBasedPolicy(),
            context = context,
            longTermMemory = FakeMemory(),
            dialogue = FakeDialogue(),
        )
        val ask = brain.process(UserRequest("включи фонарик"))
        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.NEEDS_CONFIRMATION, ask.status)
        val done = brain.process(UserRequest("да"))
        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.PROCESSED, done.status)
    }

    @Test
    fun `assistant reply is added to conversation context`() {
        val context = ConversationContext()
        val registry = ToolRegistry().apply { register(FakeFlashlightTool()) }
        val brain = AssistantOrchestrator(
            brainRouter = TestRouter(),
            skillRetriever = SkillRetriever(SkillManifestRegistry(), k = 6),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            toolPolicy = RiskBasedPolicy(),
            context = context,
            longTermMemory = FakeMemory(),
            dialogue = FakeDialogue()
        )

        brain.process(UserRequest("выключи фонарик"))

        assertEquals(
            listOf("user: выключи фонарик", "assistant: Фонарик включён."),
            context.messages
        )
    }

    @Test
    fun `replay last tool after ещё раз`() {
        val context = ConversationContext()
        val registry = ToolRegistry().apply { register(FakeFlashlightTool()) }
        val brain = AssistantOrchestrator(
            brainRouter = TestRouter(),
            skillRetriever = SkillRetriever(SkillManifestRegistry(), k = 6),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            toolPolicy = RiskBasedPolicy(),
            context = context,
            longTermMemory = FakeMemory(),
            dialogue = FakeDialogue(),
        )
        val first = brain.process(UserRequest("включи фонарик"))
        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.PROCESSED, first.status)
        assertNotNull(context.lastToolCall)

        val again = brain.process(UserRequest("ещё раз"))
        assertEquals(com.jarvis.core.common.model.AssistantResponseStatus.PROCESSED, again.status)
        assertEquals("Фонарик включён.", again.replyText)
        assertEquals("flashlight", again.toolResult?.toolId)
    }

    /** Фейковый диалог без Context. */
    private class FakeDialogue : DialogueResponder(null) {
        override fun respond(userText: String, history: List<String>, memories: String): String =
            "hello-from-fake"

        override fun respondDetailed(
            userText: String,
            history: List<String>,
            memories: String,
            assembled: com.jarvis.core.llm.AssembledLlmContext?,
        ): DialogueResult = DialogueResult(
            text = "hello-from-fake",
            kind = DialogueResult.Kind.TEMPLATE,
            reason = "fake",
        )
    }

    /** Фейковая LongTermMemory без SQLite (юнит-тесты не имеют Context). */
    private class FakeMemory : LongTermMemoryInterface {
        private val facts = mutableListOf<LongTermMemory.MemoryFact>()
        override fun retrieve(query: String, limit: Int): List<LongTermMemory.MemoryFact> = emptyList()
        override fun add(text: String, category: String, source: String): Boolean {
            facts += LongTermMemory.MemoryFact(
                id = facts.size.toLong() + 1,
                text = text,
                category = category,
                createdAt = System.currentTimeMillis(),
                source = source
            )
            return true
        }
        override fun all(): List<LongTermMemory.MemoryFact> = facts.toList()
        override fun remove(id: Long): Boolean = facts.removeAll { it.id == id }
        override fun clear() = facts.clear()
        override fun count(): Int = facts.size
    }
}