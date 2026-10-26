package com.jarvis.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * SessionService для системного voice interaction.
 * Создаёт лёгкую [JarvisVoiceInteractionSession] без отдельного UI-оверлея.
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "onNewSession")
        return JarvisVoiceInteractionSession(this)
    }

    companion object {
        private const val TAG = "Jarvis.VIS.SessionSvc"
    }
}
