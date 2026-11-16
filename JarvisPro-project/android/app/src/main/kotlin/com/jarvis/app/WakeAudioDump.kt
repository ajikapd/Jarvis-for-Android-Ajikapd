package com.jarvis.app

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Запись сырого микрофона для офлайн-оценки wake word (только debug, только по команде adb).
 * Пока запись идёт, [VoiceEngine] не реагирует на звук. PCM16 LE, 16 кГц, в files/ приложения.
 */
object WakeAudioDump {
    private const val TAG = "Jarvis.WakeDump"
    private const val FILE_NAME = "wake_dump.pcm"
    private const val MAX_BYTES = 16_000L * 2 * 60 * 15

    @Volatile private var out: BufferedOutputStream? = null
    private var written = 0L

    @Volatile private var replayRequested = false

    val isActive: Boolean get() = out != null

    fun requestReplay() {
        replayRequested = true
    }

    fun takeReplayRequest(): Boolean {
        if (!replayRequested) return false
        replayRequested = false
        return true
    }

    fun read(context: Context): FloatArray {
        val bytes = File(context.filesDir, FILE_NAME).readBytes()
        return FloatArray(bytes.size / 2) { i ->
            val low = bytes[i * 2].toInt() and 0xff
            ((bytes[i * 2 + 1].toInt() shl 8) or low) / 32768f
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (out != null) return
        written = 0
        out = BufferedOutputStream(FileOutputStream(File(context.filesDir, FILE_NAME)), 64 * 1024)
        Log.i(TAG, "start")
    }

    @Synchronized
    fun write(samples: FloatArray) {
        val o = out ?: return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = v.toByte()
            bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        o.write(bytes)
        written += bytes.size
        if (written >= MAX_BYTES) stop()
    }

    @Synchronized
    fun stop() {
        val o = out ?: return
        out = null
        runCatching { o.close() }
        Log.i(TAG, "stop bytes=$written")
    }

    fun delete(context: Context) {
        stop()
        File(context.filesDir, FILE_NAME).delete()
        Log.i(TAG, "deleted")
    }
}
