package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки возвращает голосовой контур, если пользователь его не выключал. */
class VoiceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val wanted = JarvisSettings.voiceWanted(context)
        VoiceHealthLog.write(context, "boot voiceWanted=$wanted")
        if (wanted) VoiceController.start(context)
    }
}
