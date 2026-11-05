package com.jarvis.brain.llm

import com.jarvis.brain.LongTermMemory
import com.jarvis.brain.LongTermMemoryInterface
import com.jarvis.core.llm.UnifiedContextBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmContextAssemblyTest {
    private class Facts(vararg items: Pair<String, String>) : LongTermMemoryInterface {
        private val facts = items.mapIndexed { i, (text, category) ->
            LongTermMemory.MemoryFact(i.toLong(), text, category, 0L, "user")
        }
        override fun retrieve(query: String, limit: Int) = emptyList<LongTermMemory.MemoryFact>()
        override fun add(text: String, category: String, source: String) = true
        override fun all() = facts
        override fun remove(id: Long) = true
        override fun clear() = Unit
        override fun count() = facts.size
    }

    /** Облачный режим: чувствительное отсекается флагом false, обычные факты должны доходить. */
    private fun memoryFor(query: String, memory: LongTermMemoryInterface): String {
        val input = LlmContextAssembly(longTermMemory = memory)
            .gatherInput(query, emptyList(), "CLASSIC", cloudMemoryAllowed = false)
        return UnifiedContextBuilder().build(input).legacyMemoriesSnippet
    }

    @Test
    fun questionAboutUser_getsFactsWithoutKeywordMatch() {
        val text = memoryFor("что ты про меня знаешь", Facts("пользователя зовут Никита" to "fact"))
        assertTrue(text, text.contains("Никита"))
    }

    @Test
    fun manyFacts_notCutToBudgetLimit() {
        val facts = Facts(*(1..7).map { "факт номер $it" to "fact" }.toTypedArray())
        val text = memoryFor("сколько мне лет", facts)
        assertTrue(text, text.contains("факт номер 7"))
    }

    @Test
    fun privateFact_neverGoesToCloud() {
        val text = memoryFor(
            "что ты про меня знаешь",
            Facts("любит кофе" to "fact", "диагноз врача" to "private"),
        )
        assertTrue(text, text.contains("кофе"))
        assertFalse(text, text.contains("диагноз"))
    }

    @Test
    fun generalQuestion_doesNotDragFacts() {
        val text = memoryFor("почему небо голубое", Facts("пользователя зовут Никита" to "fact"))
        assertFalse(text, text.contains("Никита"))
    }
}
