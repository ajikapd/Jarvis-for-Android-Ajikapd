package com.jarvis.app

import android.content.Context
import android.util.Log
import com.jarvis.tts.JarvisTtsConfig
import java.io.File

/**
 * Голоса Джарвиса на выбор. Фирменный голос вшит в приложение и при первом запуске
 * копируется в папку моделей; мужские голоса Piper скачиваются в «Голос и слух».
 */
object VoiceCatalog {
    private const val TAG = "Jarvis.Voices"
    private const val PREF = "tts_voice"
    private const val ASSET_DIR = "voices/jarvis-voice-ru"

    data class Voice(
        val id: String,
        val title: String,
        val about: String,
        /** Папка внутри models/. */
        val dir: String,
        val onnx: String,
        val speed: Float,
        /** Запись каталога моделей для скачивания; null — голос вшит в приложение. */
        val downloadId: String?,
    )

    val ALL = listOf(
        Voice("jarvis", "Джарвис", "Фирменный голос, вшит в приложение", "jarvis-voice-ru", "jarvis.onnx", 1.12f, null),
        Voice("dmitri", "Дмитрий", "Спокойный мужской голос, ~63 МБ", "vits-piper-ru_RU-dmitri-medium", "ru_RU-dmitri-medium.onnx", 1.08f, "tts_dmitri"),
        Voice("denis", "Денис", "Мягкий мужской голос, ~21 МБ", "vits-piper-ru_RU-denis-medium-int8", "ru_RU-denis-medium.onnx", 1.05f, "tts_denis"),
        Voice("ruslan", "Руслан", "Низкий мужской голос, ~21 МБ", "vits-piper-ru_RU-ruslan-medium-int8", "ru_RU-ruslan-medium.onnx", 1.05f, "tts_ruslan"),
    )

    fun selectedId(context: Context): String = JarvisSettings.prefs(context).getString(PREF, "jarvis") ?: "jarvis"

    fun select(context: Context, id: String) {
        JarvisSettings.prefs(context).edit().putString(PREF, id).apply()
    }

    private fun root(context: Context) = File(context.getExternalFilesDir(null), "models")

    fun isReady(context: Context, voice: Voice): Boolean {
        val dir = File(root(context), voice.dir)
        return File(dir, voice.onnx).isFile && File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data/ru_dict").isFile
    }

    /** Выбранный голос первым, затем фирменный и Дмитрий как запасные. Только готовые. */
    fun configs(context: Context): List<JarvisTtsConfig> {
        ensureBundled(context)
        val chosen = ALL.firstOrNull { it.id == selectedId(context) }
        val order = (listOfNotNull(chosen) + ALL).distinct()
        return order.filter { isReady(context, it) }
            .map { JarvisTtsConfig(File(root(context), it.dir), modelOnnx = it.onnx, numThreads = 4, speed = it.speed) }
    }

    /** Фирменный голос из APK в папку моделей. Копируется один раз, неполная копия перезаписывается. */
    @Synchronized
    fun ensureBundled(context: Context) {
        val voice = ALL.first()
        if (isReady(context, voice)) return
        val target = File(root(context), voice.dir)
        val started = System.currentTimeMillis()
        runCatching {
            copyAssetTree(context, ASSET_DIR, target)
            Log.i(TAG, "bundled voice copied in ${System.currentTimeMillis() - started} ms")
        }.onFailure { Log.w(TAG, "bundled voice copy failed: ${it.message}") }
    }

    private fun copyAssetTree(context: Context, asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            tmp.renameTo(target)
            return
        }
        target.mkdirs()
        children.forEach { copyAssetTree(context, "$asset/$it", File(target, it)) }
    }
}
