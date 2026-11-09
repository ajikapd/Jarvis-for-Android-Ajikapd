package com.jarvis.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.core.diagnostics.CrashReportText
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Отчёты об ошибках, которые пользователь сам пересылает разработчику.
 *
 * Ловит обычные вылеты (исключение в Kotlin) и при следующем запуске забирает у Android
 * причину прошлой смерти процесса: нативный вылет, зависание, нехватку памяти (Android 11+).
 * Отчёты лежат в files/crash и уходят только через «Поделиться» по решению человека.
 */
object CrashReports {
    private const val TAG = "Jarvis.Crash"
    private const val DIR = "crash"
    private const val PREF_LAST_EXIT = "crash_last_exit_ts"
    private const val PREF_PROMPTED = "crash_prompted_ts"
    private const val MAX_FILES = 10
    private const val TRACE_LIMIT = 40_000
    const val DEVELOPER_TELEGRAM = "ajikapd993"

    /** Прошлый процесс погиб от вылета или нехватки памяти (известно на Android 11+). */
    @Volatile var lastExitWasCrash: Boolean? = null
        private set

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, "Вылет", "Поток: ${thread.name}\n${stack(error)}") }
            previous?.uncaughtException(thread, error)
        }
        runCatching { collectExitReasons(app) }.onFailure { Log.w(TAG, "exit reasons: ${it.message}") }
    }

    /** Нерабочий раздел или шаг, который не уронил приложение, но должен попасть в отчёт. */
    fun record(context: Context, what: String, error: Throwable) {
        runCatching { save(context.applicationContext, "Ошибка: $what", stack(error)) }
    }

    fun pending(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun hasPending(context: Context) = pending(context).isNotEmpty()

    /** Есть новый отчёт, о котором человека ещё не спрашивали. Спрашиваем один раз. */
    fun takePrompt(context: Context): Boolean {
        val newest = pending(context).firstOrNull()?.lastModified() ?: return false
        val prefs = JarvisSettings.prefs(context)
        if (newest <= prefs.getLong(PREF_PROMPTED, 0L)) return false
        prefs.edit().putLong(PREF_PROMPTED, newest).apply()
        return true
    }

    /** Убрать отчёты после отправки или по отказу. */
    fun clear(context: Context) = pending(context).forEach { it.delete() }

    /**
     * «Поделиться» с отчётом: все неотправленные отчёты плюс свежий журнал в одном файле.
     * Если отчётов нет, уходит только журнал (для жалобы «что-то не так»).
     */
    fun shareIntent(context: Context): Intent {
        val app = context.applicationContext
        val parts = pending(app).take(5).map { it.readText() }
        val body = CrashReportText.build(
            meta = meta(app),
            sections = buildList {
                parts.forEachIndexed { i, p -> add("Отчёт ${i + 1}" to p) }
                add("Журнал" to recentLog())
            },
        )
        val out = File(dir(app), "share").apply { mkdirs() }.resolve("jarvis-report-${stamp()}.txt")
        out.writeText(body)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Отчёт Jarvis об ошибке")
            putExtra(
                Intent.EXTRA_TEXT,
                "Отчёт Jarvis: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. " +
                    "Файл приложен. Разработчик: t.me/$DEVELOPER_TELEGRAM",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Отправить отчёт разработчику").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun collectExitReasons(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val prefs = JarvisSettings.prefs(app)
        val since = prefs.getLong(PREF_LAST_EXIT, 0L)
        val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
        var newest = since
        val bad = exits.filter { it.timestamp > since && it.reason in BAD_EXITS }
        lastExitWasCrash = bad.isNotEmpty()
        bad.forEach { e ->
            newest = maxOf(newest, e.timestamp)
            val trace = runCatching {
                e.traceInputStream?.bufferedReader()?.use { r ->
                    val buf = CharArray(TRACE_LIMIT)
                    val n = r.read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
            }.getOrNull().orEmpty()
            val body = buildString {
                append("Причина: ").append(reasonName(e.reason)).append('\n')
                append("Когда: ").append(format(e.timestamp)).append('\n')
                append("Процесс: ").append(e.processName).append(", важность ").append(e.importance).append('\n')
                append("Память: ").append(e.pss / 1024).append(" МБ\n")
                e.description?.let { append("Описание: ").append(it).append('\n') }
                if (trace.isNotBlank()) append('\n').append(trace)
            }
            save(app, "Прошлый запуск: ${reasonName(e.reason)}", body, at = e.timestamp)
        }
        val latest = exits.maxOfOrNull { it.timestamp } ?: newest
        prefs.edit().putLong(PREF_LAST_EXIT, maxOf(newest, latest)).apply()
    }

    private val BAD_EXITS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "вылет"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "нативный вылет"
        ApplicationExitInfo.REASON_ANR -> "зависание"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "нехватка памяти"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "перерасход ресурсов"
        else -> "код $reason"
    }

    private fun save(app: Context, title: String, body: String, at: Long = System.currentTimeMillis()) {
        val d = dir(app)
        d.listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_FILES - 1)?.forEach { it.delete() }
        File(d, "crash-$at.txt").writeText("# $title (${format(at)})\n$body\n")
        Log.w(TAG, "report saved: $title")
    }

    private fun meta(app: Context): List<Pair<String, String>> {
        val am = app.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        return listOf(
            "Версия Jarvis" to (version ?: "?"),
            "Устройство" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Процессор" to "${Build.SUPPORTED_ABIS.joinToString()} ${soc()}",
            "Возможности процессора" to cpuFeatures(),
            "Память" to "${mem.totalMem / 1_048_576} МБ, свободно ${mem.availMem / 1_048_576} МБ",
            "Интерфейс" to (UiMode.style(app)?.id ?: "не выбран"),
            "Время" to format(System.currentTimeMillis()),
        )
    }

    private fun soc(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    private fun cpuFeatures(): String = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()
    }.getOrNull() ?: "?"

    /** Последние строки журнала своего процесса: Android отдаёт приложению только его собственные записи. */
    private fun recentLog(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "time", "*:I").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull().orEmpty()

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun dir(c: Context) = File(c.filesDir, DIR).apply { mkdirs() }

    private fun format(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ts))

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
}
