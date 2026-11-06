package com.jarvis.tools

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.reminder.Reminder
import com.jarvis.core.reminder.ReminderBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReminderToolTest {
    private val now = LocalDateTime.of(2026, 11, 5, 14, 10)

    private class Book : ReminderBook {
        val items = mutableListOf<Reminder>()
        override fun add(at: LocalDateTime, text: String, daily: Boolean, command: Boolean) =
            Reminder(items.size + 1L, at, text, daily, command).also { items += it }
        override fun all() = items.toList()
        override fun remove(id: Long) = items.removeIf { it.id == id }
    }

    private val book = Book()
    private val tool = ReminderTool(book) { now }

    private fun run(vararg args: Pair<String, Any>) = tool.execute(ToolCall(toolId = "reminder", requestId = "r", arguments = mapOf(*args)))

    @Test fun addsWithSpokenTime() {
        val r = run("phrase" to "напомни завтра в 9 позвонить маме")
        assertEquals(ToolResultStatus.SUCCESS, r.status)
        assertEquals("Напомню завтра в 9:00, сэр: позвонить маме.", r.message)
        assertEquals("позвонить маме", book.items.single().text)
    }

    @Test fun scheduledCommand() {
        val r = run("phrase" to "через 10 минут выключи фонарик", "command" to true)
        assertEquals("Выполню через 10 мин, сэр: выключи фонарик.", r.message)
        assertTrue(book.items.single().command)
    }

    @Test fun asksWhenTimeMissing() {
        val r = run("phrase" to "напомни купить хлеб")
        assertEquals(ToolResultStatus.AMBIGUOUS, r.status)
        assertTrue(book.items.isEmpty())
    }

    @Test fun acceptsTimeWithoutText() =
        assertEquals("Напомню завтра в 9:00, сэр.", run("phrase" to "напомни завтра в 9").message)

    @Test fun daily() = assertEquals(
        "Буду напоминать каждый день в 8:00, сэр: пить таблетки.",
        run("phrase" to "каждый день в 8 утра пить таблетки").message,
    )

    @Test fun listIsSorted() {
        run("phrase" to "напомни послезавтра в 10 утра второе")
        run("phrase" to "напомни завтра в 10 утра первое")
        assertEquals(
            "Напоминаний: 2. завтра в 10:00: первое; послезавтра в 10:00: второе.",
            run("action" to "list").message,
        )
    }

    @Test fun cancelSingle() {
        run("phrase" to "напомни завтра в 9 позвонить")
        assertEquals(ToolResultStatus.SUCCESS, run("action" to "cancel").status)
        assertTrue(book.items.isEmpty())
    }

    @Test fun cancelAsksWhenSeveral() {
        run("phrase" to "напомни завтра в 9 позвонить маме")
        run("phrase" to "напомни завтра в 10 купить хлеб")
        assertEquals(ToolResultStatus.AMBIGUOUS, run("action" to "cancel").status)
        assertEquals(2, book.items.size)
    }

    @Test fun cancelByQuery() {
        run("phrase" to "напомни завтра в 9 позвонить маме")
        run("phrase" to "напомни завтра в 10 купить хлеб")
        run("action" to "cancel", "query" to "хлеб")
        assertEquals("позвонить маме", book.items.single().text)
    }

    @Test fun cancelAll() {
        run("phrase" to "напомни завтра в 9 позвонить маме")
        run("phrase" to "напомни завтра в 10 купить хлеб")
        run("action" to "cancel", "all" to true)
        assertTrue(book.items.isEmpty())
    }
}
