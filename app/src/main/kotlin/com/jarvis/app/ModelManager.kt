package com.jarvis.app

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Скачивание / статус / удаление моделей в `getExternalFilesDir()/models`.
 */
class ModelManager(context: Context) {

    data class Status(
        val id: String,
        val state: State,
        val progress: Float = 0f,
        val message: String = "",
        val bytesDownloaded: Long = 0L,
        val bytesTotal: Long = 0L,
    )

    enum class State { MISSING, DOWNLOADING, READY, ERROR, CORRUPT }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()

    private val _statuses = MutableStateFlow(ModelCatalog.ALL.associate { it.id to probe(it) })
    val statuses: StateFlow<Map<String, Status>> = _statuses.asStateFlow()

    fun modelsRoot(): File = File(app.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun status(id: String): Status = _statuses.value[id] ?: Status(id, State.MISSING)

    fun refreshAll() {
        _statuses.value = ModelCatalog.ALL.associate { it.id to probe(it) }
    }

    fun download(id: String) {
        val entry = ModelCatalog.byId(id) ?: return
        if (jobs[id]?.isActive == true) return
        if (!hasSpace(entry.sizeBytes)) {
            publish(Status(id, State.ERROR, message = "Недостаточно места (~${entry.sizeBytes / 1_000_000} МБ)."))
            return
        }
        cancelFlags[id] = false
        publish(Status(id, State.DOWNLOADING, progress = 0f, message = "Скачиваю…", bytesTotal = entry.sizeBytes))
        jobs[id] = scope.launch {
            try {
                when (entry.packageKind) {
                    ModelCatalog.PackageKind.SINGLE_FILE -> downloadSingle(entry)
                    ModelCatalog.PackageKind.TAR_BZ2 -> downloadAndExtractTarBz2(entry)
                }
                val ready = probe(entry)
                publish(ready)
                if (ready.state == State.READY) {
                    onModelReady(entry)
                }
            } catch (t: Throwable) {
                if (cancelFlags[id] == true) {
                    publish(Status(id, State.MISSING, message = "Отменено"))
                } else {
                    Log.e(TAG, "download $id failed: ${t.message}", t)
                    publish(Status(id, State.ERROR, message = t.message ?: t.javaClass.simpleName))
                }
            } finally {
                jobs.remove(id)
                cancelFlags.remove(id)
            }
        }
    }

    fun cancel(id: String) {
        cancelFlags[id] = true
        jobs[id]?.cancel()
    }

    fun delete(id: String) {
        cancel(id)
        val entry = ModelCatalog.byId(id) ?: return
        runCatching {
            File(modelsRoot(), entry.readyMarker).delete()
            if (entry.packageKind == ModelCatalog.PackageKind.SINGLE_FILE) {
                File(modelsRoot(), entry.relativePath).delete()
            } else if (entry.relativeDir.isNotBlank()) {
                File(modelsRoot(), entry.relativeDir).deleteRecursively()
            }
            File(modelsRoot(), entry.relativePath).delete()
            File(modelsRoot(), "${entry.relativePath}.part").delete()
        }
        publish(probe(entry))
        if (entry.kind == ModelCatalog.Kind.CHAT_LLM) {
            BrainGraph.shutdownChatLlm()
        }
        if (entry.kind == ModelCatalog.Kind.TOOL_LLM) {
            BrainGraph.shutdownLlmBrain()
            BrainGraph.invalidate()
        }
    }

    private fun onModelReady(entry: ModelCatalog.Entry) {
        when (entry.kind) {
            ModelCatalog.Kind.CHAT_LLM -> {
                BrainGraph.invalidateChatLlm()
                BrainGraph.preloadChatModel(app)
                BrainGraph.warmChatLlm(app)
            }
            ModelCatalog.Kind.TOOL_LLM -> {
                BrainGraph.shutdownLlmBrain()
                BrainGraph.invalidate()
            }
            ModelCatalog.Kind.TTS -> Unit // JarvisSpeaker читает каталог при следующем старте сервиса
            ModelCatalog.Kind.ASR -> Unit
        }
    }

    private fun probe(entry: ModelCatalog.Entry): Status {
        val marker = File(modelsRoot(), entry.readyMarker)
        if (!marker.isFile) {
            // int8 TTS alias used historically
            if (entry.id == "tts_dmitri") {
                val alt = File(modelsRoot(), "vits-piper-ru_RU-dmitri-medium-int8/ru_RU-dmitri-medium.onnx")
                if (alt.isFile) {
                    return Status(entry.id, State.READY, progress = 1f, message = "Готово", bytesDownloaded = alt.length())
                }
            }
            return Status(entry.id, State.MISSING)
        }
        if (entry.packageKind == ModelCatalog.PackageKind.SINGLE_FILE &&
            marker.length() < entry.expectedMinBytes
        ) {
            return Status(entry.id, State.CORRUPT, message = "Файл слишком маленький")
        }
        return Status(
            entry.id, State.READY, progress = 1f, message = "Готово",
            bytesDownloaded = marker.length(), bytesTotal = marker.length(),
        )
    }

    private fun publish(status: Status) {
        _statuses.value = _statuses.value.toMutableMap().apply { put(status.id, status) }
    }

    private fun hasSpace(needBytes: Long): Boolean {
        val root = modelsRoot()
        val stat = StatFs(root.absolutePath)
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return free > needBytes + 50_000_000L
    }

    private fun downloadSingle(entry: ModelCatalog.Entry) {
        val dest = File(modelsRoot(), entry.relativePath)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        httpDownload(entry.url, part, entry) { done, total ->
            val p = if (total > 0) done.toFloat() / total else 0f
            publish(
                Status(
                    entry.id, State.DOWNLOADING, progress = p.coerceIn(0f, 1f),
                    message = "Скачиваю…", bytesDownloaded = done, bytesTotal = max(total, entry.sizeBytes),
                ),
            )
        }
        if (cancelFlags[entry.id] == true) {
            part.delete()
            return
        }
        if (part.length() < entry.expectedMinBytes) {
            part.delete()
            throw IllegalStateException("Скачанный файл слишком маленький")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    private fun downloadAndExtractTarBz2(entry: ModelCatalog.Entry) {
        val archive = File(modelsRoot(), entry.relativePath)
        archive.parentFile?.mkdirs()
        val part = File(archive.parentFile, "${archive.name}.part")
        httpDownload(entry.url, part, entry) { done, total ->
            val p = if (total > 0) (done.toFloat() / total) * 0.85f else 0f
            publish(
                Status(
                    entry.id, State.DOWNLOADING, progress = p.coerceIn(0f, 0.85f),
                    message = "Скачиваю архив…", bytesDownloaded = done, bytesTotal = max(total, entry.sizeBytes),
                ),
            )
        }
        if (cancelFlags[entry.id] == true) {
            part.delete()
            return
        }
        if (archive.exists()) archive.delete()
        if (!part.renameTo(archive)) {
            part.copyTo(archive, overwrite = true)
            part.delete()
        }
        publish(Status(entry.id, State.DOWNLOADING, progress = 0.9f, message = "Распаковываю…"))
        val outDir = File(modelsRoot(), entry.relativeDir)
        if (outDir.exists()) outDir.deleteRecursively()
        extractTarBz2(archive, modelsRoot())
        // sherpa archives often contain a top-level folder matching relativeDir
        archive.delete()
        val marker = File(modelsRoot(), entry.readyMarker)
        if (!marker.isFile) {
            // Sometimes tar root name differs slightly — search
            val found = modelsRoot().walkTopDown().firstOrNull {
                it.name == marker.name && it.isFile
            }
            if (found != null && entry.relativeDir.isNotBlank()) {
                val expectedParent = File(modelsRoot(), entry.relativeDir)
                if (!expectedParent.exists()) {
                    found.parentFile?.renameTo(expectedParent)
                }
            }
        }
        if (!File(modelsRoot(), entry.readyMarker).isFile) {
            throw IllegalStateException("После распаковки нет ${entry.readyMarker}")
        }
    }

    private fun httpDownload(
        urlStr: String,
        dest: File,
        entry: ModelCatalog.Entry,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        var url = URL(urlStr)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            if (cancelFlags[entry.id] == true) return
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "JarvisAssistant/0.1")
            }
            val code = conn.responseCode
            if (code in 301..399) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(loc)
                redirects++
                if (redirects > 8) throw IllegalStateException("Слишком много редиректов")
                continue
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else entry.sizeBytes }
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        if (cancelFlags[entry.id] == true) break
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastEmit > 512 * 1024 || done == total) {
                            onProgress(done, total)
                            lastEmit = done
                        }
                    }
                }
            }
            conn.disconnect()
            return
        }
        throw IllegalStateException("Не удалось скачать")
    }

    private fun extractTarBz2(archive: File, destRoot: File) {
        BufferedInputStream(archive.inputStream()).use { bin ->
            BZip2CompressorInputStream(bin).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val out = File(destRoot, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "Jarvis.Models"

        @Volatile private var instance: ModelManager? = null

        fun get(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
