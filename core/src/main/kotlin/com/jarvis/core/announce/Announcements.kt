package com.jarvis.core.announce

/** Входящее сообщение из уведомления; текст уже прошёл маскирование. */
data class IncomingMessage(
    val appPackage: String,
    val sender: String,
    val text: String,
    val group: Boolean = false,
)

/** Что произносить без просьбы: фильтр приложений, защита от шквала и повторов. */
class MessageAnnouncer(private val minGapMs: Long = DEFAULT_GAP_MS) {
    private var lastSpokenAt = Long.MIN_VALUE / 2
    private var lastMessage = ""

    fun phrase(message: IncomingMessage, nowMs: Long): String? {
        val app = APPS[message.appPackage] ?: return null
        val sender = cleanSender(message.sender)
        if (message.group || sender.isBlank() || message.text.isBlank()) return null
        val id = "$sender|${message.text}"
        if (id == lastMessage || nowMs - lastSpokenAt < minGapMs) return null
        lastMessage = id
        lastSpokenAt = nowMs
        val text = message.text.trim().trimEnd('.').take(MAX_TEXT)
        return "Сэр, $sender в $app: $text."
    }

    companion object {
        private const val DEFAULT_GAP_MS = 20_000L
        private const val MAX_TEXT = 160
        private val COUNTER = Regex("\\s*\\(\\d+[^)]*\\)\\s*$")
        private val APPS = mapOf(
            "org.telegram.messenger" to "Телеграме", "org.telegram.messenger.web" to "Телеграме",
            "org.thunderdog.challegram" to "Телеграме", "com.whatsapp" to "Ватсапе",
            "com.vkontakte.android" to "ВКонтакте", "com.viber.voip" to "Вайбере",
            "com.google.android.apps.messaging" to "сообщениях", "com.android.mms" to "сообщениях",
        )

        fun cleanSender(raw: String): String = raw.replace(COUNTER, "").trim()
    }
}

object CallAnnouncer {
    fun phrase(contactName: String?): String =
        if (contactName.isNullOrBlank()) "Сэр, звонок с неизвестного номера." else "Сэр, звонит $contactName."
}

/** Тихие часы: ночью новости сообщений подождут до утра. */
object QuietHours {
    private const val START = 23
    private const val END = 8

    fun isQuiet(hour: Int): Boolean = hour >= START || hour < END
}
