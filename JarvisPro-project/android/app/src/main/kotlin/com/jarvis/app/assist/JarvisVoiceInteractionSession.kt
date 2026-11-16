package com.jarvis.app.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.jarvis.app.VoiceController

/**
 * Системная voice session → единый [VoiceController] / [com.jarvis.app.VoiceService].
 *
 * Stage 4.5: не открываем Dashboard автоматически (cold start без UI),
 * не создаём второй ASR/TTS pipeline. RecognitionService stub — ок.
 */
class JarvisVoiceInteractionSession(
    context: Context,
) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "session created")
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow flags=$showFlags → VoicePipeline")
        handoffToVoicePipeline("onShow")
        // Session UI не нужен: pipeline уже в VoiceService.
        hide()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        Log.i(TAG, "onHandleAssist → VoicePipeline (payload ignored until Memory)")
        handoffToVoicePipeline("onHandleAssist")
        hide()
    }

    override fun onHide() {
        Log.d(TAG, "onHide")
        super.onHide()
    }

    private fun handoffToVoicePipeline(reason: String) {
        val appCtx = context.applicationContext
        runCatching {
            VoiceController.startFromSystemAssist(appCtx)
            Log.i(TAG, "handoff ok reason=$reason entry=${VoiceController.ENTRY_SYSTEM_ASSIST}")
        }.onFailure {
            Log.e(TAG, "handoff failed reason=$reason: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Jarvis.VIS.Session"
    }
}
