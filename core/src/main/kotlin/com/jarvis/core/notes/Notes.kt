package com.jarvis.core.notes

/** Хранилище списков: покупки, дела, заметки. */
interface NoteBook {
    fun items(list: String): List<String>
    fun add(list: String, items: List<String>)
    fun remove(list: String, item: String): Boolean
    fun clear(list: String)
}

sealed class NoteCommand {
    abstract val list: String

    data class Add(override val list: String, val items: List<String>) : NoteCommand()
    data class Read(override val list: String) : NoteCommand()
    data class Remove(override val list: String, val item: String) : NoteCommand()
    data class Clear(override val list: String) : NoteCommand()
}

/** Разбор просьб о списках; всё, что сюда не подходит, остаётся другим командам. */
object NoteParser {
    const val SHOPPING = "покупки"
    const val TODO = "дела"
    const val NOTES = "заметки"

    private val LISTS = listOf(
        Regex("(?:в |из |на )?(?:список |списке |списка )?покуп\\S*") to SHOPPING,
        Regex("(?:в |из |на )?(?:список |списке |списка )дел\\b|(?:в |из )дела\\b") to TODO,
        Regex("(?:в |из )?замет\\S*") to NOTES,
    )
    private val ADD = setOf("добавь", "запиши", "внеси")
    private val READ = setOf("что", "какие", "прочитай", "покажи", "прочти", "озвучь")
    private val REMOVE = setOf("убери", "удали", "вычеркни")
    private val CLEAR = setOf("очисти", "сотри")
    private val SEPARATOR = Regex(",\\s*|\\s+и\\s+")

    fun parse(phrase: String): NoteCommand? {
        val text = phrase.lowercase().trim().trimEnd('.', '!', '?', '…')
        val first = text.substringBefore(' ')
        val thought = Regex("^(?:запиши|запомни) мысль[:\\s]+(.+)$").find(text)?.groupValues?.get(1)
        if (thought != null) return NoteCommand.Add(NOTES, listOf(thought))
        val (match, list) = LISTS.firstNotNullOfOrNull { (re, name) -> re.find(text)?.let { it to name } } ?: return null
        val rest = text.removeRange(match.range).replace(Regex("\\s+"), " ").trim()
        val body = rest.substringAfter(' ', "").trim()
        return when (first) {
            in ADD -> items(body).takeIf { it.isNotEmpty() }?.let { NoteCommand.Add(list, it) }
            in CLEAR -> NoteCommand.Clear(list)
            in REMOVE -> if (body.isEmpty() || body == "всё" || body == "все") NoteCommand.Clear(list) else NoteCommand.Remove(list, body)
            in READ -> NoteCommand.Read(list)
            else -> null
        }
    }

    private fun items(body: String): List<String> =
        body.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
}

object NoteSpeech {
    private val EMPTY = mapOf(NoteParser.SHOPPING to "Список покупок пуст, сэр.", NoteParser.TODO to "Список дел пуст, сэр.")
    private val TITLE = mapOf(NoteParser.SHOPPING to "В покупках", NoteParser.TODO to "В делах", NoteParser.NOTES to "В заметках")

    fun read(list: String, items: List<String>): String {
        if (items.isEmpty()) return EMPTY[list] ?: "Заметок нет, сэр."
        return "${TITLE.getValue(list)}: ${items.joinToString(", ")}."
    }
}
