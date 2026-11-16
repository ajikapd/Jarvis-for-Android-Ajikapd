package com.jarvis.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Системный Voice Interaction Service — квалификация ROLE_ASSISTANT.
 * Тонкий вход: session → [VoiceController.startFromSystemAssist].
 * Не владеет ASR/TTS/Brain — только [com.jarvis.app.VoiceService].
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "ready (pipeline owner = VoiceService)")
    }

    override fun onShutdown() {
        Log.i(TAG, "shutdown")
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.i(TAG, "launch from keyguard → showSession")
        showSession(Bundle(), 0)
    }

    companion object {
        private const val TAG = "Jarvis.VIS"
    }
}
