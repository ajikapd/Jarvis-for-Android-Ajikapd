package com.jarvis.tools

import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.notes.NoteBook
import com.jarvis.core.notes.NoteCommand
import com.jarvis.core.notes.NoteParser
import com.jarvis.core.notes.NoteSpeech

/** Голосовые списки: покупки, дела, заметки. */
class NotesTool(private val book: NoteBook) : Tool {
    override val definition = ToolDefinition(
        id = "notes", name = "Заметки и списки", description = "Покупки, дела и заметки: добавить, прочитать, вычеркнуть",
        parameters = listOf(ToolParameter("phrase", "просьба словами пользователя", ParameterType.STRING, required = true)),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val command = NoteParser.parse(call.arguments["phrase"]?.toString().orEmpty())
            ?: return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что записать, сэр?")
        val reply = when (command) {
            is NoteCommand.Add -> {
                book.add(command.list, command.items)
                "Записал, сэр."
            }
            is NoteCommand.Read -> NoteSpeech.read(command.list, book.items(command.list))
            is NoteCommand.Remove -> if (book.remove(command.list, command.item)) "Вычеркнул, сэр." else "Такого в списке нет, сэр."
            is NoteCommand.Clear -> {
                book.clear(command.list)
                "Список очищен, сэр."
            }
        }
        return ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, reply)
    }
}
