package com.jarvis.core.aioutput

import com.jarvis.core.common.model.ToolCall

/**
 * Вызов инструмента, предложенный облачной моделью одной строкой: `ACT {"tool":"…","args":{…}}`.
 * Модель только предлагает: исполнение идёт обычным путём с политикой риска и подтверждением,
 * а белый список не даёт вызвать экранные инструменты и всё, что не описано в [PROMPT].
 */
object CloudToolAction {
    const val MARKER = "ACT"

    private val ALLOWED = setOf(
        "flashlight", "system.status", "web.answer", "notes", "app.action", "news", "rates", "location", "reminder", "briefing", "navigate", "web.search", "camera", "volume", "brightness", "timer", "launcher", "music", "weather",
        "system.setting", "screenshot", "phone.call", "sms", "telegram.send", "calendar",
        "contacts.search", "notifications.list",
    )

    val PROMPT = """
        Если пользователь просит сделать что-то на телефоне, не отвечай текстом и никогда не говори, что сделал сам.
        Ответь ровно одной строкой без пояснений: $MARKER {"tool":"<id>","args":{...}}
        Если для ответа нужны свежие данные (погода, календарь, состояние телефона), тоже вызови инструмент: ответишь по его результату.
        На остальные вопросы и разговор отвечай обычным текстом.
        Инструменты:
        flashlight {"action":"on|off"}
        system.status {}
        web.answer {"query":"вопрос о свежих событиях, ценах, результатах, людях сейчас"}
        notes {"phrase":"просьба про покупки, дела или заметки своими словами"}
        app.action {"kind":"youtube|maps|market|taxi|site","query":"что искать"}
        news {}
        rates {}
        location {}
        briefing {}
        navigate {"destination":"куда"}
        web.search {"query":"что найти в браузере"}
        camera {"mode":"photo|video"}
        reminder {"action":"add|list|cancel","phrase":"что и когда, словами пользователя","query":"какое отменить"}
        volume {"direction":"up|down|max|mute|unmute"} или {"direction":"set","percent":0-100}
        brightness {"direction":"up|down|max"} или {"direction":"set","percent":0-100}
        timer {"minutes":N,"seconds":N} или {"action":"cancel|status"} или {"action":"alarm","hour":0-23,"minute":0-59}
        launcher {"action":"open","app":"название"}
        music {"action":"play","query":"что включить"} или {"action":"pause|resume|next|previous|status"}
        weather {"city":"город","day":0|1|2}
        system.setting {"name":"wifi|bluetooth|airplane|battery|display|sound|storage|apps|location|notifications|security","value":"open"}
        screenshot {}
        phone.call {"contact":"имя"} или {"action":"history"}
        sms {"action":"send","recipient":"имя","text":"текст"}
        telegram.send {"chat":"имя","text":"текст"}
        calendar {"action":"list"}
        contacts.search {"query":"имя"}
        notifications.list {"action":"list"}
    """.trimIndent()

    /** Текст может оказаться вызовом: держим его, пока не станет ясно, можно ли озвучивать. */
    fun mayBeAction(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith(MARKER) || (t.length < MARKER.length && MARKER.startsWith(t))
    }

    fun isAction(text: String): Boolean = text.trimStart().startsWith("$MARKER ")

    /** @return вызов либо null, если это не вызов, он испорчен или инструмента нет в списке. */
    fun parse(text: String, requestId: String): ToolCall? {
        if (!isAction(text)) return null
        val body = text.trimStart().removePrefix(MARKER).trim()
        val root = runCatching { MiniJson.parse(body) }.getOrNull() ?: return null
        val tool = root.opt("tool")?.asString()?.takeIf { it in ALLOWED } ?: return null
        val args = (root.opt("args") as? JsonVal.Obj)?.map.orEmpty()
            .mapNotNull { (key, value) -> plain(value)?.let { key to it } }
            .toMap()
        return ToolCall(requestId = requestId, toolId = tool, arguments = args)
    }

    private fun plain(value: JsonVal): Any? = when (value) {
        is JsonVal.Str -> value.value
        is JsonVal.Bool -> value.value
        is JsonVal.Num -> if (value.value == value.value.toInt().toDouble()) value.value.toInt() else value.value
        else -> null
    }
}
