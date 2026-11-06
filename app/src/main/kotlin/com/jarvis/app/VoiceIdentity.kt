package com.jarvis.app

import android.content.Context
import com.jarvis.asr.SpeakerEmbedder
import com.jarvis.wakeword.VoicePrint
import com.jarvis.wakeword.VoiceTrim
import java.io.File

/**
 * «Только мой голос»: третий фильтр после двух детекторов слова.
 * Образец хранится только в личной папке приложения. Если сравнить нельзя, слово проходит:
 * лишнее срабатывание лучше, чем Джарвис, не отвечающий владельцу.
 */
object VoiceIdentity {
    const val MODEL_ID = "speaker_campp"
    private const val PRINT_FILE = "voice_print.txt"

    data class Check(val accepted: Boolean, val score: Float, val threshold: Float)

    private var embedder: SpeakerEmbedder? = null
    private var print: VoicePrint? = null
    private var printLoaded = false

    private fun modelFile(context: Context): File? {
        val entry = ModelCatalog.byId(MODEL_ID) ?: return null
        return File(ModelManager.get(context).modelsRoot(), entry.relativePath).takeIf { it.isFile }
    }

    private fun printFile(context: Context) = File(context.filesDir, PRINT_FILE)

    fun modelReady(context: Context): Boolean = modelFile(context) != null

    @Synchronized
    fun enrolled(context: Context): Boolean = currentPrint(context) != null

    @Synchronized
    private fun currentPrint(context: Context): VoicePrint? {
        if (!printLoaded) {
            print = runCatching { VoicePrint.decode(printFile(context).readText()) }.getOrNull()
            printLoaded = true
        }
        return print
    }

    /** Отпечаток записи для образца или null, если речи мало. */
    @Synchronized
    fun embedSample(context: Context, pcm: FloatArray): FloatArray? = embed(context, pcm)

    @Synchronized
    fun save(context: Context, samples: List<FloatArray>) {
        val next = VoicePrint.enroll(samples)
        printFile(context).writeText(next.encode())
        print = next
        printLoaded = true
    }

    @Synchronized
    fun forget(context: Context) {
        printFile(context).delete()
        print = null
        printLoaded = true
    }

    /** Загрузка заранее: первое слово не должно ждать модель. */
    @Synchronized
    fun warm(context: Context) {
        if (JarvisSettings.voiceLock(context) && currentPrint(context) != null) loadEmbedder(context)
    }

    /** null, когда защита выключена или сравнить нечего. */
    @Synchronized
    fun check(context: Context, pcm: FloatArray): Check? {
        if (!JarvisSettings.voiceLock(context)) return null
        val owner = currentPrint(context) ?: return null
        val embedding = embed(context, pcm) ?: return null
        val score = owner.score(embedding)
        return Check(score >= owner.threshold, score, owner.threshold)
    }

    private fun embed(context: Context, pcm: FloatArray): FloatArray? {
        val speech = VoiceTrim.speech(pcm)
        if (speech.isEmpty()) return null
        val engine = loadEmbedder(context) ?: return null
        return runCatching { engine.embed(speech) }.getOrNull()
    }

    private fun loadEmbedder(context: Context): SpeakerEmbedder? {
        embedder?.let { return it }
        val model = modelFile(context) ?: return null
        return runCatching { SpeakerEmbedder(model.absolutePath) }.getOrNull()?.also { embedder = it }
    }
}
