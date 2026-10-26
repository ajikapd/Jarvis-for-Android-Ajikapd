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
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.Slot
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.skills.SkillManifestRegistry
import com.jarvis.core.skills.SkillRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 3: результат инструмента (SMS/уведомления) озвучивается полностью,
 * но в [ConversationContext] попадает только masked-версия.
 */
class SecretHistoryMaskingTest {

    /** Инструмент, который «прочитал SMS» с кодом. */
    private class FakeSmsTool : Tool {
        override val definition = ToolDefinition(
            id = "sms",
            name = "SMS",
            description = "Тестовая реализация чтения SMS",
            parameters = emptyList(),
            riskLevel = RiskLevel.SAFE,
            onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        )

        override fun execute(call: ToolCall): ToolResult = ToolResult(
            requestId = call.requestId,
            toolId = definition.id,
            status = ToolResultStatus.SUCCESS,
            message = "Последние SMS: от Банк (10:00): Ваш код 482913, никому не сообщайте.",
        )
    }

    private class TestRouter : BrainRouter {
        override fun route(
            request: UserRequest,
            history: List<String>,
            topSkills: List<BrainRouter.SkillInfo>,
        ): BrainResult = BrainResult.SkillCall(
            intent = Intent(
                type = IntentType.SEND_SMS,
                normalizedText = request.text,
                slots = mapOf("action" to Slot("action", "read")),
                confidence = 0.95f,
                confidenceLevel = ConfidenceLevel.HIGH,
            ),
            confidence = 0.95f,
        )
    }

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

    private class FakeMemory : LongTermMemoryInterface {
        override fun retrieve(query: String, limit: Int): List<LongTermMemory.MemoryFact> = emptyList()
        override fun add(text: String, category: String, source: String): Boolean = true
        override fun all(): List<LongTermMemory.MemoryFact> = emptyList()
        override fun remove(id: Long): Boolean = true
        override fun clear() = Unit
        override fun count(): Int = 0
    }

    private fun orchestrator(context: ConversationContext): AssistantOrchestrator {
        val registry = ToolRegistry().apply { register(FakeSmsTool()) }
        return AssistantOrchestrator(
            brainRouter = TestRouter(),
            skillRetriever = SkillRetriever(SkillManifestRegistry(), k = 6),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            toolPolicy = RiskBasedPolicy(),
            context = context,
            longTermMemory = FakeMemory(),
            dialogue = FakeDialogue(),
        )
    }

    @Test
    fun spokenReplyKeepsCodeButHistoryIsMasked() {
        val context = ConversationContext()
        val response = orchestrator(context).process(UserRequest("прочитай смс"))

        // Пользователь слышит/видит полный ответ.
        assertTrue(response.replyText.contains("482913"))
        // В историю уходит masked-версия.
        val stored = context.messages.last()
        assertTrue(stored.startsWith("assistant:"))
        assertFalse("code leaked into history: $stored", stored.contains("482913"))
        assertTrue(stored.contains("[скрыто]"))
    }

    @Test
    fun historyKeepsOrdinaryNumbers() {
        val context = ConversationContext()
        val response = orchestrator(context).process(UserRequest("прочитай смс"))
        assertEquals(response.replyText.contains("Банк"), context.messages.last().contains("Банк"))
    }
}
