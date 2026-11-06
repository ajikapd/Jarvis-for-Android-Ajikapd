package com.jarvis.brain

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ForgetFactToolTest {
    private class Store(texts: List<String>) : LongTermMemoryInterface {
        val items = texts.mapIndexed { i, t -> LongTermMemory.MemoryFact(i.toLong(), t, "preference", 0L, "user") }.toMutableList()
        override fun retrieve(query: String, limit: Int) = emptyList<LongTermMemory.MemoryFact>()
        override fun add(text: String, category: String, source: String) = false
        override fun all(): List<LongTermMemory.MemoryFact> = items.toList()
        override fun remove(id: Long) = items.removeIf { it.id == id }
        override fun clear() = items.clear()
        override fun count() = items.size
    }

    private fun forget(store: Store, q: String) =
        ForgetFactTool { store }.execute(ToolCall(requestId = "r", toolId = "memory.forget", arguments = mapOf("query" to q)))

    @Test fun removesMatchingFact() {
        val store = Store(listOf("Пользователь любит кофе", "Живёт в Москве"))
        assertEquals("Забыл, сэр.", forget(store, "кофе").message)
        assertEquals(1, store.items.size)
    }

    @Test fun inflectedQueryMatches() {
        val store = Store(listOf("Живёт в Москве"))
        forget(store, "москву")
        assertEquals(0, store.items.size)
    }

    @Test fun unknownFact() = assertEquals("Такого я не помню, сэр.", forget(Store(listOf("кофе")), "чай").message)

    @Test fun emptyAsks() = assertEquals(ToolResultStatus.AMBIGUOUS, forget(Store(emptyList()), "").status)
}
