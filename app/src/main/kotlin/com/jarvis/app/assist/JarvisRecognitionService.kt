package com.jarvis.app.assist

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.util.Log

/**
 * Stub RecognitionService — требуется meta-data VoiceInteractionService.
 * Реальное ASR остаётся в существующем offline-пайплайне [com.jarvis.app.VoiceService].
 */
class JarvisRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        Log.d(TAG, "onStartListening (stub → forward to VoiceService path)")
        try {
            listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {
        }
    }

    override fun onCancel(listener: Callback) {
        Log.d(TAG, "onCancel")
    }

    override fun onStopListening(listener: Callback) {
        Log.d(TAG, "onStopListening")
    }

    companion object {
        private const val TAG = "Jarvis.Recognition"
    }
}
