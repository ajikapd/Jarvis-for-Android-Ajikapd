package com.jarvis.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал здоровья голосового контура в файл: отвалы случаются без USB, когда logcat недоступен.
 * Только состояние и уровни звука, текста речи здесь нет.
 */
object VoiceHealthLog {
    private const val TAG = "Jarvis.VoiceHealth"
    private const val FILE_NAME = "voice_health.log"
    private const val MAX_BYTES = 512 * 1024L

    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun write(context: Context, line: String) {
        Log.i(TAG, line)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                val rotated = File(context.filesDir, "$FILE_NAME.1")
                rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText("${time.format(Date())} $line\n")
        }
    }
}
