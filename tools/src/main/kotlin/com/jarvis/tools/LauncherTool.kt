package com.jarvis.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.Transliterator
import com.jarvis.screencontrol.ActionCoordinator
import com.jarvis.screencontrol.ScreenController

/**
 * Управление приложениями:
 * - open / list
 * - minimize (Home) — «закрой/сверни»
 * - force_stop — killBackgroundProcesses + экран «О приложении» (CONFIRM в orchestrator)
 */
class LauncherTool(private val context: Context) : Tool {

    private val coordinator by lazy { ActionCoordinator(context) }

    override val definition = ToolDefinition(
        id = "launcher",
        name = "Приложения",
        description = "Открыть, свернуть (Home), принудительно остановить или перечислить приложения",
        parameters = listOf(
            ToolParameter(
                "action",
                "open, list, minimize/close или force_stop",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("open", "list", "minimize", "close", "force_stop"),
            ),
            ToolParameter(
                name = "app",
                description = "Название приложения (для open/minimize/force_stop)",
                type = ParameterType.STRING,
                required = false,
            ),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
        requiredPermissions = listOf("android.permission.KILL_BACKGROUND_PROCESSES"),
    )

    override fun execute(call: ToolCall): ToolResult {
        val action = call.arguments["action"]?.toString()?.lowercase()
            ?: if (call.arguments["app"] != null) "open" else "list"
        return when (action) {
            "list" -> listApps(call.requestId)
            "minimize", "close" -> minimizeApp(call.requestId, call.arguments["app"]?.toString())
            "force_stop", "forcestop", "kill" -> forceStopApp(call.requestId, call.arguments["app"]?.toString())
            else -> openApp(call.requestId, call.arguments["app"]?.toString())
        }
    }

    private fun openApp(requestId: String, queryRaw: String?): ToolResult {
        val query = queryRaw?.lowercase()?.trim()?.takeUnless { it in OPEN_VERBS }
        if (query.isNullOrBlank()) {
            return ToolResult(requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что открыть, сэр?")
        }
        return try {
            val pm = context.packageManager
            val intent = resolvePackage(query)?.let { pm.getLaunchIntentForPackage(it) }
            if (intent == null) {
                ToolResult(requestId, definition.id, ToolResultStatus.FAILED, notFound(query))
            } else {
                val pkg = intent.component?.packageName ?: "приложение"
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                // Дождаться появления пакета в a11y-дереве (HyperOS race)
                if (ScreenController.isAvailable()) {
                    coordinator.waitForPackage(pkg, timeoutMs = 3_500L)
                } else {
                    Thread.sleep(800)
                }
                ToolResult(
                    requestId,
                    definition.id,
                    ToolResultStatus.SUCCESS,
                    "Открыл.",
                    mapOf("app" to pkg),
                )
            }
        } catch (t: Throwable) {
            ToolResult(requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private fun listApps(requestId: String): ToolResult {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val labels = pm.queryIntentActivities(main, 0)
            .mapNotNull { it.loadLabel(pm)?.toString() }
            .distinct()
            .sorted()
        return ToolResult(
            requestId,
            definition.id,
            ToolResultStatus.SUCCESS,
            "Установлено приложений: ${labels.size}. Например: ${labels.take(12).joinToString(", ")}",
            mapOf("count" to labels.size),
        )
    }

    /** Свернуть на Home (не kill). */
    private fun minimizeApp(requestId: String, queryRaw: String?): ToolResult {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(home)
        if (ScreenController.isAvailable()) {
            ScreenController.globalAction("home")
        }
        val name = queryRaw?.takeIf { it.isNotBlank() } ?: "приложение"
        return ToolResult(
            requestId,
            definition.id,
            ToolResultStatus.SUCCESS,
            "Свернул.",
            mapOf("action" to "minimize"),
        )
    }

    /**
     * Best-effort force stop: killBackgroundProcesses + экран деталей приложения
     * (полный force-stop без system UID недоступен сторонним приложениям).
     */
    private fun forceStopApp(requestId: String, queryRaw: String?): ToolResult {
        val query = queryRaw?.lowercase()?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult(
                requestId, definition.id, ToolResultStatus.AMBIGUOUS,
                "Какое приложение принудительно закрыть?",
            )
        }
        val pkg = resolvePackage(query)
            ?: return ToolResult(
                requestId, definition.id, ToolResultStatus.FAILED,
                notFound(query),
            )
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.killBackgroundProcesses(pkg)
            // Открыть «О приложении», чтобы пользователь/дальнейший UI мог нажать «Остановить»
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(details)
            if (ScreenController.isAvailable()) {
                coordinator.waitForPackage(pkg, timeoutMs = 2_500L)
            }
            ToolResult(
                requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                "Остановил фоновые процессы «$pkg» и открыл сведения о приложении. " +
                    "Полный force-stop — кнопкой «Остановить» в настройках (ограничение Android).",
                mapOf("action" to "force_stop", "app" to pkg),
            )
        } catch (t: Throwable) {
            ToolResult(requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    /**
     * Пакет по названию: точный пакет, затем синоним человека, затем название приложения
     * (сначала точное, потом по звучанию: «ватсапп бизнес» → WhatsApp Business).
     */
    fun resolvePackage(query: String): String? {
        val pm = context.packageManager
        if (runCatching { pm.getLaunchIntentForPackage(query) }.getOrNull() != null) return query
        AppAliases(context).resolve(query)?.let { pkg ->
            if (pm.getLaunchIntentForPackage(pkg) != null) return pkg
        }
        findByLabel(query)?.component?.packageName?.let { return it }
        val apps = launcherApps()
        // «Карты» от Яндекса находятся и по «яндекс карты»: бренд берётся из имени пакета.
        val branded = apps.mapNotNull { (pkg, label) -> AppMatcher.brandOf(pkg)?.let { pkg to "$it $label" } }
        return AppMatcher.best(query, apps + branded)
    }

    private fun notFound(query: String) =
        "Не нашёл приложение «$query». Если оно называется иначе, добавьте синоним: «Ещё → Приложения»."

    /** Все приложения с иконкой: (пакет, название). */
    fun launcherApps(): List<Pair<String, String>> = runCatching {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName to (it.loadLabel(pm)?.toString() ?: "") }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.first }
    }.getOrDefault(emptyList())

    private fun findByLabel(query: String): Intent? = runCatching {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val appName = query.replaceFirstChar { it.uppercase() }
        val q = normalizeName(query)

        pm.queryIntentActivities(main, 0)
            .asSequence()
            .map { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(pm)?.toString() ?: ""
                Triple(pkg, label, normalizeName(label))
            }
            .map { (pkg, label, norm) ->
                val score = matchScore(query, appName, q, label, norm)
                Triple(pkg, label, score)
            }
            .filter { it.third >= STRONG_MATCH }
            .maxByOrNull { it.third }
            ?.let { (pkg, _, _) -> pm.getLaunchIntentForPackage(pkg) }
    }.getOrNull()

    private fun matchScore(
        rawQuery: String,
        capitalized: String,
        normalizedQuery: String,
        label: String,
        normalizedLabel: String,
    ): Int {
        if (label.isBlank()) return 0
        val lc = label.lowercase()
        val qlc = rawQuery.lowercase()
        return when {
            lc == qlc -> 100
            normalizedLabel == normalizedQuery && normalizedQuery.isNotEmpty() -> 95
            lc.startsWith(qlc) || lc.contains(qlc) -> 80
            normalizedLabel.contains(normalizedQuery) && normalizedQuery.isNotEmpty() -> 70
            label.startsWith(capitalized, ignoreCase = true) -> 60
            else -> 0
        }
    }

    private fun normalizeName(value: String): String = Transliterator.compareKey(value)

    private companion object {
        val OPEN_VERBS = setOf("открой", "запусти", "открыть", "запустить", "приложение")
        const val STRONG_MATCH = 80
    }
}
