package com.jarvis.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jarvis.asr.SpeakerEmbedder
import com.jarvis.wakeword.VoiceTrim
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Вторая проверка голоса на слове «Джарвис» — моделью ERes2NetV2.
 *
 * Замер на записях владельца (52 «Джарвис») и чужого голоса (20): при пороге 0,36 она
 * отказывает владельцу в 19% случаев и пропускает 15% чужих; CAM++ на тех же записях
 * пропускала 35% чужих. Модель медленнее, поэтому идёт фоном: команда слушается сразу,
 * а выполнится, только если проверка не сказала «чужой».
 *
 * Эталон строится из записей образцов голоса, сохранённых при записи в «Только мой голос»
 * (files/voice_enroll): после смены модели голос не нужно записывать заново.
 */
object VoiceSecondCheck {
    const val MODEL_ID = "speaker_eres2netv2"
    private const val TAG = "Jarvis.Voice2"
    private const val ENROLL_DIR = "voice_enroll"
    private const val PRINT_FILE = "voice_print_eres2netv2.txt"
    const val THRESHOLD = 0.36f
    private const val RATE = 16_000

    data class Result(val accepted: Boolean, val score: Float, val ms: Long)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-voice2") }
    private var embedder: SpeakerEmbedder? = null
    private var print: FloatArray? = null
    private var printKey: String? = null

    private fun enrollDir(context: Context) = File(context.filesDir, ENROLL_DIR)

    private fun modelFile(context: Context): File? {
        val entry = ModelCatalog.byId(MODEL_ID) ?: return null
        return File(ModelManager.get(context).modelsRoot(), entry.relativePath).takeIf { it.isFile }
    }

    fun modelReady(context: Context) = modelFile(context) != null

    /** Включена «Только мой голос», модель скачана и есть записанные образцы. */
    fun active(context: Context): Boolean =
        JarvisSettings.voiceLock(context) && modelReady(context) && enrollFiles(context).isNotEmpty()

    /** Образец голоса со страницы записи: храним звук, чтобы строить эталон для любой модели. */
    fun keepEnrollment(context: Context, kind: String, pcm: FloatArray) {
        runCatching {
            val dir = enrollDir(context).apply { mkdirs() }
            writeWav(File(dir, "${System.currentTimeMillis()}_$kind.wav"), pcm)
        }.onFailure { Log.w(TAG, "keep enrollment: ${it.message}") }
    }

    /** Голос стёрт или перезаписывается: старые образцы больше не эталон. */
    @Synchronized
    fun forget(context: Context) {
        enrollDir(context).deleteRecursively()
        File(context.filesDir, PRINT_FILE).delete()
        print = null
        printKey = null
    }

    /** Фоновая проверка куска с «Джарвис». null — проверка сейчас невозможна. */
    fun checkAsync(context: Context, pcm: FloatArray): CompletableFuture<Result?>? {
        if (!active(context)) return null
        val app = context.applicationContext
        val copy = pcm.copyOf()
        return CompletableFuture.supplyAsync({ runCatching { check(app, copy) }.getOrNull() }, worker)
    }

    /** Заранее, в фоне: модель и эталон готовы к первому «Джарвис». */
    fun warm(context: Context) {
        if (!active(context)) return
        val app = context.applicationContext
        worker.execute { runCatching { ownerPrint(app) } }
    }

    @Synchronized
    private fun check(context: Context, pcm: FloatArray): Result? {
        val started = SystemClock.elapsedRealtime()
        val owner = ownerPrint(context) ?: return null
        val engine = loadEmbedder(context) ?: return null
        val speech = VoiceTrim.speech(pcm)
        if (speech.isEmpty()) return null
        val score = VoiceTrim.windows(speech).mapNotNull { piece ->
            runCatching { engine.embed(piece) }.getOrNull()?.let { dot(normalize(it), owner) }
        }.maxOrNull() ?: return null
        return Result(score >= THRESHOLD, score, SystemClock.elapsedRealtime() - started)
    }

    private fun enrollFiles(context: Context): List<File> {
        seedFromClips(context)
        return enrollDir(context).listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
    }

    /**
     * Образцы, записанные до появления этой проверки, лежат среди записей для замера
     * (отладочная сборка). Берём их, чтобы не просить записывать голос заново.
     */
    private fun seedFromClips(context: Context) {
        val dir = enrollDir(context)
        if (dir.listFiles()?.isNotEmpty() == true) return
        val clips = File(context.filesDir, "voice_clips").listFiles { f -> f.name.contains("_enroll_") } ?: return
        if (clips.isEmpty()) return
        // Только последняя запись голоса: образцы одного захода, не старше суток друг от друга.
        val latest = clips.maxOf { it.lastModified() }
        dir.mkdirs()
        clips.filter { latest - it.lastModified() < 24 * 3600_000L }
            .forEach { runCatching { it.copyTo(File(dir, "${it.lastModified()}_${it.name}"), overwrite = true) } }
        Log.i(TAG, "enrollment seeded from voice clips: ${dir.listFiles()?.size}")
    }

    @Synchronized
    private fun ownerPrint(context: Context): FloatArray? {
        val files = enrollFiles(context)
        if (files.isEmpty()) return null
        val key = "${files.size}:${files.last().name}"
        if (print != null && printKey == key) return print
        val cached = File(context.filesDir, PRINT_FILE)
        runCatching {
            val lines = cached.readLines()
            if (lines.size == 2 && lines[0] == key) {
                print = lines[1].split(',').map { it.toFloat() }.toFloatArray()
                printKey = key
                return print
            }
        }
        val engine = loadEmbedder(context) ?: return null
        val started = SystemClock.elapsedRealtime()
        val embeddings = files.flatMap { file ->
            val pcm = runCatching { readWav(file) }.getOrNull() ?: return@flatMap emptyList()
            VoiceTrim.windows(VoiceTrim.speech(pcm)).mapNotNull { piece ->
                runCatching { engine.embed(piece) }.getOrNull()?.let(::normalize)
            }
        }
        if (embeddings.isEmpty()) return null
        val mean = FloatArray(embeddings[0].size)
        for (e in embeddings) for (i in mean.indices) mean[i] += e[i]
        val next = normalize(mean)
        runCatching { cached.writeText(key + "\n" + next.joinToString(",")) }
        Log.i(TAG, "owner print from ${files.size} samples, ${embeddings.size} pieces, ${SystemClock.elapsedRealtime() - started} ms")
        print = next
        printKey = key
        return next
    }

    private fun loadEmbedder(context: Context): SpeakerEmbedder? {
        embedder?.let { return it }
        val model = modelFile(context) ?: return null
        val started = SystemClock.elapsedRealtime()
        return runCatching { SpeakerEmbedder(model.absolutePath) }
            .onFailure { Log.w(TAG, "model load failed: ${it.message}") }
            .getOrNull()?.also {
                embedder = it
                Log.i(TAG, "model loaded in ${SystemClock.elapsedRealtime() - started} ms")
            }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val n = sqrt(sum).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - 44) / 2) { buf.short / 32768f }
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            data[i * 2] = (v and 0xff).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write("RIFF".toByteArray()); out.write(le32(36 + data.size))
            out.write("WAVEfmt ".toByteArray()); out.write(le32(16))
            out.write(le16(1)); out.write(le16(1)); out.write(le32(RATE)); out.write(le32(RATE * 2))
            out.write(le16(2)); out.write(le16(16))
            out.write("data".toByteArray()); out.write(le32(data.size)); out.write(data)
        }
    }
}
