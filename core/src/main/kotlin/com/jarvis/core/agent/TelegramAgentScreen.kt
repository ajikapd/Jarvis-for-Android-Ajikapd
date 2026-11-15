package com.jarvis.core.agent

/** Ориентиры интерфейса Telegram по дереву доступности окна. */
object TelegramAgentScreen {
    private val savedLabels = setOf("избранное", "saved messages", "сохранённые сообщения")
    private val backLabels = setOf("назад", "back", "go back")
    private val menuLabels = setOf("дополнительные параметры", "more options", "меню", "открыть меню", "open menu")
    private val attachmentsSearch = setOf("поиск сообщений и тегов", "search messages and tags")
    private val composer = Regex("^(?:сообщение|message)(?::|$)", RegexOption.IGNORE_CASE)
    private val openDetails = Regex(
        "(?:^|\\s)(?:открой|покажи|найди|посмотри|перейди|зайди)\\s+(?:в\\s+|на\\s+)?" +
            "(?:профил[ьяе]|страниц[уыае]|вложени[яе]|медиа|информацию\\s+о\\s+чате)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val draft = Regex(
        """^(?:(?:открой (?:телеграм\w*|telegram) и )?(?:открой|найди) (?:чат )?([«"]?(?:избранное|saved messages)[»"]?)[, ]+(?:и )?)?(?:введи|напиши) (?:в (?:чате )?([«"]?(?:избранном|избранное|saved messages)[»"]?) )?(?:текст )?[«"]([^»"\n]+)[»"][, ]+(?:но )?(?:без отправки|не отправляй)[.!]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val chatLookup = Regex(
        """^(?:открой (?:телеграм\w*|telegram)[, ]+и |в (?:телеграм\w*|telegram) )?(?:найди|открой|покажи) чат (?:с )?[«"]([^»"\n]+)[»"](?: в (?:телеграм\w*|telegram))?[.!]?$""",
        RegexOption.IGNORE_CASE,
    )

    data class Conversation(val title: String, val header: Int, val editor: Int)

    fun conversation(nodes: List<RawNode>): Conversation? {
        val window = nodes.firstOrNull() ?: return null
        val back = nodes.firstOrNull { it.clickable && key(it.text) in backLabels } ?: return null
        val editor = nodes.indexOfLast {
            it.editable && !it.password && composer.containsMatchIn(it.text) && it.top >= back.bottom
        }
        if (editor < 0) return null
        // Центральная область шапки отделена от боковых кнопок. Пункт меню и строка
        // результата лежат ниже шапки и не могут стать заголовком текущей беседы.
        val center = window.left + (window.right - window.left) / 2
        val header = nodes.indices.filter { i ->
            val node = nodes[i]
            node.clickable && !node.editable && node.top < back.bottom && node.bottom == back.bottom &&
                node.left > window.left && node.right < window.right && node.left < center && node.right > center
        }.minByOrNull { nodes[it].area } ?: return null
        val title = nodes.filter {
            !it.clickable && !it.editable && it.text.isNotBlank() && nodes[header].contains(it)
        }.minByOrNull { it.top }?.text ?: return null
        return Conversation(title, header, editor)
    }

    fun isSaved(title: String): Boolean = key(title) in savedLabels

    fun draftText(goal: String): String? =
        draft.matchEntire(goal.trim())?.groupValues?.let { parts ->
            parts[3].takeIf { parts[1].isNotEmpty() || parts[2].isNotEmpty() }
        }

    fun lookupTitle(goal: String): String? = chatLookup.matchEntire(goal.trim())?.groupValues?.get(1)

    fun isMessageHint(text: String): Boolean = key(text) in setOf("сообщение", "message")

    fun protectsHeader(goal: String): Boolean = !openDetails.containsMatchIn(goal.substringBefore('«').substringBefore('"'))

    fun isHeaderTarget(nodes: List<RawNode>, conversation: Conversation, target: Int): Boolean {
        val header = nodes[conversation.header]
        val node = nodes[target]
        // tapCenter может подняться от подписи к её кликабельному родителю.
        if (header.contains(node)) return true
        val x = node.left + (node.right - node.left) / 2
        val y = node.top + (node.bottom - node.top) / 2
        return x in header.left until header.right && y in header.top until header.bottom
    }

    fun savedNavigation(nodes: List<RawNode>): Int? {
        val label = nodes.firstOrNull { !it.editable && isSaved(it.text) }
        val menuItem = label?.let { item ->
            nodes.indices.filter { nodes[it].clickable && nodes[it].contains(item) && nodes[it].top > nodes.first().top }
                .minByOrNull { nodes[it].area }
        }
        if (menuItem != null) return menuItem
        if (nodes.any { key(it.text) in attachmentsSearch }) return null
        return nodes.indexOfFirst { it.clickable && key(it.text) in menuLabels }.takeIf { it >= 0 }
    }

    private fun key(text: String): String = text.trim().lowercase()
}
