package com.jarvis.core.nativeandroid

import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.common.model.RiskLevel

/**
 * Stage 14: готовность native Android tool к agent pipeline.
 * Не заменяет CatalogToolRegistry — классифицирует допуск к live exec.
 */
enum class NativeToolExecMode {
    /** Можно live через SafeAutonomousExecutor / FIRST_SAFE path. */
    LIVE_DEVICE_SAFE,
    /** Live через ToolExecutor, только read-only side effects. */
    LIVE_READ_ONLY,
    /** Только dry-run / gate; live запрещён на Stage 14. */
    GATED,
    /** Не для agent loop (UI automation / destructive). */
    BLOCKED,
}

data class NativeToolSpec(
    val toolId: String,
    val category: String,
    val actionKind: ActionKind,
    val riskLevel: RiskLevel,
    val execMode: NativeToolExecMode,
    val notes: List<String> = emptyList(),
)

/**
 * Stage 14: каталог native tools, уже существующих в `:tools`.
 * Все действия по-прежнему проходят Risk → Policy → Gate.
 */
object NativeAndroidToolCatalog {

    val ALL: List<NativeToolSpec> = listOf(
        spec("flashlight", "device", ActionKind.LOCAL_DEVICE_SAFE, RiskLevel.SAFE, NativeToolExecMode.LIVE_DEVICE_SAFE),
        spec("volume", "device", ActionKind.LOCAL_DEVICE_SAFE, RiskLevel.SAFE, NativeToolExecMode.LIVE_DEVICE_SAFE),
        spec("brightness", "device", ActionKind.LOCAL_DEVICE_SAFE, RiskLevel.SAFE, NativeToolExecMode.LIVE_DEVICE_SAFE),
        spec("weather", "info", ActionKind.READ_ONLY, RiskLevel.SAFE, NativeToolExecMode.LIVE_READ_ONLY),
        spec("knowledge", "info", ActionKind.READ_ONLY, RiskLevel.SAFE, NativeToolExecMode.LIVE_READ_ONLY),
        spec("clipboard", "system", ActionKind.READ_ONLY, RiskLevel.LOW, NativeToolExecMode.LIVE_READ_ONLY,
            notes = listOf("read_preferred")),
        spec("timer", "device", ActionKind.LOCAL_DEVICE_SAFE, RiskLevel.LOW, NativeToolExecMode.GATED,
            notes = listOf("stage14_gated_pending_args")),
        spec("music", "media", ActionKind.MEDIA_CONTROL, RiskLevel.LOW, NativeToolExecMode.GATED),
        spec("launcher", "apps", ActionKind.LAUNCH_APP, RiskLevel.CONFIRM, NativeToolExecMode.GATED),
        spec("system.setting", "settings", ActionKind.SYSTEM_SETTINGS, RiskLevel.CONFIRM, NativeToolExecMode.GATED),
        spec("calendar", "info", ActionKind.READ_ONLY, RiskLevel.LOW, NativeToolExecMode.GATED),
        spec("contacts.search", "info", ActionKind.READ_ONLY, RiskLevel.CONFIRM, NativeToolExecMode.GATED),
        spec("notifications.list", "system", ActionKind.READ_ONLY, RiskLevel.LOW, NativeToolExecMode.GATED),
        spec("files.search", "files", ActionKind.READ_ONLY, RiskLevel.LOW, NativeToolExecMode.GATED,
            notes = listOf("search_only")),
        spec("sms", "comms", ActionKind.COMMUNICATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("phone.call", "comms", ActionKind.COMMUNICATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("telegram.send", "comms", ActionKind.COMMUNICATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("screenshot", "ui", ActionKind.UI_AUTOMATION, RiskLevel.CONFIRM, NativeToolExecMode.BLOCKED),
        spec("ui.click", "ui", ActionKind.UI_AUTOMATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("ui.type", "ui", ActionKind.UI_AUTOMATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("ui.swipe", "ui", ActionKind.UI_AUTOMATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("ui.global", "ui", ActionKind.UI_AUTOMATION, RiskLevel.SENSITIVE, NativeToolExecMode.BLOCKED),
        spec("ui.read", "ui", ActionKind.UI_AUTOMATION, RiskLevel.CONFIRM, NativeToolExecMode.BLOCKED),
        spec("ui.wait", "ui", ActionKind.UI_AUTOMATION, RiskLevel.LOW, NativeToolExecMode.BLOCKED),
    )

    private val byId = ALL.associateBy { it.toolId }

    fun find(toolId: String): NativeToolSpec? = byId[toolId]

    fun liveAllowed(toolId: String): Boolean {
        val mode = byId[toolId]?.execMode ?: return false
        return mode == NativeToolExecMode.LIVE_DEVICE_SAFE ||
            mode == NativeToolExecMode.LIVE_READ_ONLY
    }

    fun liveDeviceSafeIds(): Set<String> =
        ALL.filter { it.execMode == NativeToolExecMode.LIVE_DEVICE_SAFE }.map { it.toolId }.toSet()

    fun liveReadOnlyIds(): Set<String> =
        ALL.filter { it.execMode == NativeToolExecMode.LIVE_READ_ONLY }.map { it.toolId }.toSet()

    fun size(): Int = ALL.size

    private fun spec(
        toolId: String,
        category: String,
        kind: ActionKind,
        risk: RiskLevel,
        mode: NativeToolExecMode,
        notes: List<String> = emptyList(),
    ) = NativeToolSpec(toolId, category, kind, risk, mode, notes)
}
