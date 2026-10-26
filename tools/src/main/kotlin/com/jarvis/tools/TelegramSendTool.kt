package com.jarvis.tools

import android.content.Context
import android.content.Intent
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.ContactPhraseNormalizer
import com.jarvis.core.nlp.Transliterator
import com.jarvis.screencontrol.ActionCoordinator
import com.jarvis.screencontrol.ScreenController

/**
 * Полный сценарий: открыть Telegram → найти названный чат → ввести текст → Отправить.
 */
class TelegramSendTool(private val context: Context) : Tool {

    private val coordinator by lazy { ActionCoordinator(context) }

    override val definition = ToolDefinition(
        id = "telegram.send",
        name = "Написать в Telegram",
        description = "Открывает Telegram, находит указанный чат/контакт, вводит сообщение и нажимает Отправить",
        parameters = listOf(
            ToolParameter("chat", "Имя чата или контакта", ParameterType.STRING, required = true),
            ToolParameter("text", "Текст сообщения", ParameterType.STRING, required = true),
        ),
        riskLevel = RiskLevel.LOW,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val text = call.arguments["text"]?.toString().orEmpty().trim()
        if (text.isBlank()) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.AMBIGUOUS,
                "Не указан текст сообщения",
            )
        }
        val chatRaw = call.arguments["chat"]?.toString().orEmpty().trim()
        if (chatRaw.isBlank()) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.AMBIGUOUS,
                "Не указан чат или контакт",
            )
        }
        val chatLabel = ContactPhraseNormalizer.canonical(chatRaw).ifBlank { chatRaw }
        val queries = ContactPhraseNormalizer.searchQueries(chatRaw)
            .ifEmpty { listOf(chatLabel) }

        if (!ScreenController.isAvailable()) {
            return ToolResult(
                call.requestId, definition.id, ToolResultStatus.FAILED,
                "Служба спец. возможностей Jarvis не включена.",
            )
        }

        val steps = mutableListOf<String>()
        return try {
            if (!openTelegram()) {
                return fail(call, "Не удалось открыть Telegram.")
            }
            steps += "Открыл Telegram"
            Thread.sleep(600)

            if (queries.any { alreadyInChat(it) }) {
                steps += "Чат «$chatLabel» уже открыт"
            } else {
                if (hasMessageCompose()) {
                    coordinator.findAndClick("назад", scrollAttempts = 0, waitTimeoutMs = 1_500L)
                    Thread.sleep(700)
                }
                val search = coordinator.findAndClick("поиск", scrollAttempts = 1, waitTimeoutMs = 3_000L)
                if (!search.success) {
                    return fail(call, "Не нашёл поиск в Telegram. ${steps.joinToString(". ")}")
                }
                steps += "Поиск"
                Thread.sleep(500)

                var opened = false
                var lastErr = ""
                for (q in queries) {
                    val typed = coordinator.findAndType(null, q)
                    if (!typed.success) {
                        lastErr = typed.message
                        continue
                    }
                    steps += "Ищу «$q»"
                    Thread.sleep(1_600)
                    // Первый ряд результатов поиска (не 2–3-й по fuzzy)
                    val openChat = coordinator.findAndClick(q, scrollAttempts = 1, waitTimeoutMs = 3_500L)
                    if (openChat.success) {
                        steps += "Открыл «${openChat.matchedText ?: q}»"
                        opened = true
                        break
                    }
                    lastErr = openChat.message
                }
                if (!opened) {
                    return fail(call, "Не открыл чат «$chatLabel». $lastErr")
                }
                Thread.sleep(1_000)
            }

            val typedMsg = coordinator.findAndTypeInBottomField(text)
            if (!typedMsg.success) {
                return fail(call, "Не ввёл текст в чат. ${typedMsg.message}")
            }
            steps += "Ввёл текст"
            Thread.sleep(400)

            val sent = coordinator.findAndClick("отправить", scrollAttempts = 0, waitTimeoutMs = 2_500L)
            if (!sent.success) {
                return fail(call, "Текст введён, но «Отправить» не нажался. ${sent.message}")
            }
            steps += "Отправил"

            ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                "Написал в «$chatLabel» и отправил.",
                mapOf("chat" to chatLabel, "steps" to steps.joinToString(" → ")),
            )
        } catch (t: Throwable) {
            fail(call, t.message ?: "Ошибка отправки в Telegram")
        }
    }

    private fun fail(call: ToolCall, msg: String) =
        ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, msg)

    private fun openTelegram(): Boolean {
        val pm = context.packageManager
        val pkg = TELEGRAM_PACKAGES.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: findTelegramByLabel()
            ?: return false
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        coordinator.waitForPackage("telegram", timeoutMs = 4_000L)
        return true
    }

    private fun findTelegramByLabel(): String? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).firstOrNull { ri ->
            val label = ri.loadLabel(pm)?.toString().orEmpty()
            val key = Transliterator.compareKey(label)
            key.contains("telegram") || label.contains("телеграм", ignoreCase = true)
        }?.activityInfo?.packageName
    }

    private fun alreadyInChat(chat: String): Boolean {
        if (!hasMessageCompose()) return false
        val nodes = ScreenController.dumpActiveTree().ifEmpty { ScreenController.dumpTree() }
        val q = chat.lowercase()
        return nodes.any { n ->
            val t = n.text.lowercase()
            val d = n.contentDescription.lowercase()
            (t.contains(q) || d.contains(q)) && !n.editable
        }
    }

    private fun hasMessageCompose(): Boolean {
        val nodes = ScreenController.dumpActiveTree().ifEmpty { ScreenController.dumpTree() }
        return nodes.any { n ->
            n.editable && (
                n.text.isNotBlank() ||
                    n.contentDescription.contains("сообщен", true) ||
                    // hint не всегда в a11y; ищем EditText внизу экрана
                    (n.bounds.centerY() > (context.resources.displayMetrics.heightPixels * 0.75f))
                )
        }
    }

    companion object {
        private val TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
        )
    }
}
