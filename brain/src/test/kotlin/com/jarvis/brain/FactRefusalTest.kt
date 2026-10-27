package com.jarvis.brain

import com.jarvis.core.common.DefaultToolExecutor
import com.jarvis.core.common.RiskBasedPolicy
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.ConversationContext
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.skills.SkillManifestRegistry
import com.jarvis.core.skills.SkillRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 3: подтверждение «запомнил» заменяется отказом, если в фразе секрет,
 * и наоборот — обычный факт сохраняется в очищенном виде.
 */
class FactRefusalTest {

    private class ChatRouter : BrainRouter {
        override fun route(
            request: UserRequest,
            history: List<String>,
            topSkills: List<BrainRouter.SkillInfo>,
        ): BrainResult = BrainResult.Chat(confidence = 0.9f)
    }

    private class AckDialogue : DialogueResponder(null) {
        override fun respond(userText: String, history: List<String>, memories: String): String = "Запомнил!"

        override fun respondDetailed(
            userText: String,
            history: List<String>,
            memories: String,
            assembled: com.jarvis.core.llm.AssembledLlmContext?,
        ): DialogueResult = DialogueResult(
            text = "Запомнил!",
            kind = DialogueResult.Kind.TEMPLATE,
            reason = "fake",
        )
    }

    private class RecordingMemory : LongTermMemoryInterface {
        val added = mutableListOf<String>()
        override fun retrieve(query: String, limit: Int): List<LongTermMemory.MemoryFact> = emptyList()
        override fun add(text: String, category: String, source: String): Boolean {
            added += text
            return true
        }
        override fun all(): List<LongTermMemory.MemoryFact> = emptyList()
        override fun remove(id: Long): Boolean = true
        override fun clear() = Unit
        override fun count(): Int = added.size
    }

    private fun orchestrator(memory: RecordingMemory, context: ConversationContext = ConversationContext()) =
        AssistantOrchestrator(
            brainRouter = ChatRouter(),
            skillRetriever = SkillRetriever(SkillManifestRegistry(), k = 6),
            toolRegistry = ToolRegistry(),
            toolExecutor = DefaultToolExecutor(ToolRegistry()),
            toolPolicy = RiskBasedPolicy(),
            context = context,
            longTermMemory = memory,
            dialogue = AckDialogue(),
        )

    @Test
    fun passwordPhraseGetsRefusalInsteadOfConfirmation() {
        val memory = RecordingMemory()
        val context = ConversationContext()
        val response = orchestrator(memory, context).process(UserRequest("запомни мой пароль 12345"))

        assertEquals(FactExtractor.REFUSAL, response.replyText)
        assertTrue("secret must not reach memory", memory.added.isEmpty())
        assertFalse(context.messages.last().contains("12345"))
        assertTrue(context.messages.last().contains("не запоминаю"))
    }

    @Test
    fun codePhraseGetsRefusal() {
        val memory = RecordingMemory()
        val response = orchestrator(memory).process(UserRequest("запомни код 482913"))
        assertEquals(FactExtractor.REFUSAL, response.replyText)
        assertTrue(memory.added.isEmpty())
    }

    @Test
    fun ordinaryFactIsSavedCleanedAndConfirmed() {
        val memory = RecordingMemory()
        val response = orchestrator(memory).process(UserRequest("запомни что я люблю зелёный чай"))

        assertEquals("Запомнил!", response.replyText)
        assertEquals(listOf("Пользователь любит зелёный чай"), memory.added)
    }

    @Test
    fun ordinaryChatIsNotTreatedAsFact() {
        val memory = RecordingMemory()
        val response = orchestrator(memory).process(UserRequest("какая сегодня погода"))

        assertEquals("Запомнил!", response.replyText)
        assertTrue(memory.added.isEmpty())
    }
}
