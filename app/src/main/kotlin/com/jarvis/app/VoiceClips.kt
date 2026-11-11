package com.jarvis.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Замер моделей узнавания голоса: короткие записи «Джарвис», команд и образцов голоса
 * с оценкой текущей модели. Их сравнивают на компьютере с другими моделями.
 * Только в отладочной сборке, только в личной папке приложения, не больше [MAX_CLIPS] файлов.
 */
object VoiceClips {
    private const val DIR = "voice_clips"
    private const val INDEX = "index.tsv"
    private const val MAX_CLIPS = 600
    private const val RATE = 16_000

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-clips") }
    private val name = SimpleDateFormat("MMdd-HHmmss-SSS", Locale.US)

    val enabled: Boolean get() = BuildConfig.DEBUG

    /**
     * @param kind wake, wake_rejected, barge, command, enroll_word, enroll_phrase
     * @param note остальное для разбора: media, порог, решение
     */
    fun save(context: Context, kind: String, pcm: FloatArray, score: Float? = null, note: String = "") {
        if (!enabled || pcm.isEmpty()) return
        val app = context.applicationContext
        val copy = pcm.copyOf()
        val stamp = Date()
        io.execute {
            runCatching {
                val dir = File(app.filesDir, DIR).apply { mkdirs() }
                val file = File(dir, "${name.format(stamp)}_$kind.wav")
                writeWav(file, copy)
                val line = listOf(
                    file.name,
                    kind,
                    "%.2f".format(Locale.US, copy.size / RATE.toFloat()),
                    score?.let { "%.3f".format(Locale.US, it) } ?: "",
                    note,
                ).joinToString("\t")
                File(dir, INDEX).appendText(line + "\n")
                trim(dir)
            }
        }
    }

    private fun trim(dir: File) {
        val clips = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        if (clips.size <= MAX_CLIPS) return
        // Образцы голоса нужны как эталон: их не удаляем.
        clips.filterNot { it.name.contains("_enroll_") }
            .take(clips.size - MAX_CLIPS)
            .forEach { it.delete() }
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            data[i * 2] = (v and 0xff).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write("RIFF".toByteArray())
            out.write(le32(36 + data.size))
            out.write("WAVEfmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))
            out.write(le16(1))
            out.write(le32(RATE))
            out.write(le32(RATE * 2))
            out.write(le16(2))
            out.write(le16(16))
            out.write("data".toByteArray())
            out.write(le32(data.size))
            out.write(data)
        }
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}
