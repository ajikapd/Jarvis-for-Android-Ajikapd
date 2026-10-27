package com.jarvis.app.proactive

import android.content.Context

/**
 * Б3/B4: голосовой канал проактивной доставки.
 *
 * Б3 содержит заглушку (голос ещё не подключён); Б4 заменяет её на
 * VoiceService.ACTION_SAY с аудиофокусом. Так доставка и уведомление
 * собираются и тестируются независимо от голоса.
 */
object ProactiveVoice {

    data class Result(
        val requested: Boolean,
        val focusGranted: Boolean,
        val accepted: Boolean,
        val note: String = "",
    )

    fun speak(context: Context, text: String): Result =
        Result(requested = false, focusGranted = false, accepted = false, note = "not_wired")
}
