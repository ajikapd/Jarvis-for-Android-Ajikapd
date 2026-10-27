package com.jarvis.app.proactive

import android.content.Context
import android.content.Intent
import com.jarvis.app.VoiceController
import com.jarvis.app.VoiceService
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState

/**
 * Б4: голосовой канал проактивной доставки.
 *
 * Микрофонный сервис ради одной фразы не поднимаем: если VoiceService не
 * запущен ([VoiceController.hasEngine] == false), доставка остаётся
 * уведомлением. Если идёт диалог или Jarvis уже говорит — тоже не говорим
 * (предложение уже записано в журнал как подавленное/тихое).
 *
 * Аудиофокус запрашивает и снимает сам VoiceService (это единственный
 * владелец TTS); сюда возвращается только факт отправки команды.
 */
object ProactiveVoice {

    data class Result(
        val requested: Boolean,
        val note: String = "",
    )

    fun speak(context: Context, text: String): Result {
        if (text.isBlank()) return Result(requested = false, note = "empty_text")
        if (!VoiceController.hasEngine) return Result(requested = false, note = "no_engine")
        if (VoiceController.isSpeaking) {
            return Result(requested = false, note = "already_speaking")
        }
        val state = JarvisEvents.assistantState.value
        val session = VoiceController.isLiveSession ||
            state == AssistantState.LISTENING ||
            state == AssistantState.THINKING ||
            state == AssistantState.SPEAKING
        if (session) return Result(requested = false, note = "session_active")

        val intent = Intent(context, VoiceService::class.java)
            .setAction(VoiceService.ACTION_SAY)
            .putExtra(VoiceService.EXTRA_TEXT, text)
        return runCatching {
            context.startService(intent)
            Result(requested = true, note = "sent")
        }.getOrElse { e ->
            Result(requested = false, note = "start_failed:${e.message}")
        }
    }
}
