package com.jarvis.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.View

object HudFeedback {
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun key(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun beep(context: Context, tone: Int = ToneGenerator.TONE_PROP_ACK) {
        if (!JarvisSettings.hudSfx(context)) return
        if (JarvisSettings.prefs(context).getBoolean("companion_quiet", false)) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour >= 23 || hour < 7) return
        }
        runCatching {
            val gen = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            gen.startTone(tone, 70)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ gen.release() }, 180)
        }
    }
}
