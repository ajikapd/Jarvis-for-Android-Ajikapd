package com.jarvis.tools

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.notes.NoteBook
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesToolTest {
    private class Book : NoteBook {
        val lists = HashMap<String, MutableList<String>>()
        override fun items(list: String) = lists[list].orEmpty()
        override fun add(list: String, items: List<String>) { lists.getOrPut(list) { mutableListOf() } += items }
        override fun remove(list: String, item: String) = lists[list]?.removeIf { it.contains(item) } == true
        override fun clear(list: String) { lists.remove(list) }
    }

    private val tool = NotesTool(Book())
    private fun say(phrase: String) =
        tool.execute(ToolCall(requestId = "r", toolId = "notes", arguments = mapOf("phrase" to phrase))).message

    @Test fun fullCycle() {
        assertEquals("Записал, сэр.", say("запиши в покупки молоко и хлеб"))
        assertEquals("В покупках: молоко, хлеб.", say("что в списке покупок"))
        assertEquals("Вычеркнул, сэр.", say("вычеркни молоко из покупок"))
        assertEquals("В покупках: хлеб.", say("что в покупках"))
        assertEquals("Список очищен, сэр.", say("очисти список покупок"))
        assertEquals("Список покупок пуст, сэр.", say("прочитай покупки"))
    }
}
