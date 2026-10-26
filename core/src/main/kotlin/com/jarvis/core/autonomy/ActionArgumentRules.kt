package com.jarvis.core.autonomy

/**
 * Stage 8D: правила аргументов + декларативные permission requirements.
 * Не запрашивает permissions у Android — только описывает / валидирует.
 */
object ActionArgumentRules {

    val GLOBAL_FORBIDDEN_KEYS: Set<String> = setOf(
        "password", "passwd", "secret", "token", "api_key", "apikey",
        "auth", "authorization", "cookie", "session", "private_key",
        "shell", "command", "exec", "script", "javascript", "raw_html",
        "intent_extra", "bundle", "parcelable", "uri_override",
        "accessibility_action", "gesture", "inject_event",
    )

    fun allowedKeysFor(action: ProposedAction): Set<String> {
        val tool = action.toolId?.lowercase().orEmpty()
        val id = action.actionId.lowercase()
        return when {
            action.kind == ActionKind.READ_ONLY || action.kind == ActionKind.NONE ->
                setOf("query", "limit")
            tool == "flashlight" || id == "flashlight" || id == "torch" ->
                setOf("action")
            tool == "volume" || id.startsWith("volume") ->
                setOf("action", "level", "stream")
            tool == "brightness" || id.startsWith("brightness") ->
                setOf("action", "level")
            tool == "music" || action.kind == ActionKind.MEDIA_CONTROL ->
                setOf("action", "query", "package")
            tool == "launcher" || action.kind == ActionKind.LAUNCH_APP ->
                setOf("package", "name", "action", "app")
            tool == "weather" || id.contains("weather") ->
                setOf("query", "city")
            tool == "knowledge" ->
                setOf("query", "topic", "limit", "exclude_title", "title")
            tool == "timer" -> setOf("action", "duration", "seconds", "label")
            tool == "calendar" -> setOf("action", "query", "title", "when", "duration")
            tool == "clipboard" -> setOf("action", "text")
            tool == "sms" || tool == "telegram.send" || tool == "phone.call" ->
                setOf("action", "contact", "number", "text", "chat", "message", "history")
            tool == "contacts.search" || tool == "contacts" ->
                setOf("query", "name")
            tool == "files.search" || tool == "files" ->
                setOf("action", "query", "path", "uri")
            tool == "system.setting" -> setOf("action", "key", "value", "setting")
            tool == "notifications.list" || tool == "notifications" ->
                setOf("action", "limit")
            tool == "screenshot" -> setOf("action")
            tool.startsWith("ui.") ->
                setOf(
                    "action", "text", "x", "y", "dx", "dy", "selector", "timeout",
                    "direction", "count", "key", "value",
                )
            else -> emptySet() // unknown → no free args (fail-closed on any key)
        }
    }

    /**
     * Декларативные permission/capability ids для kind/tool.
     * Пустой набор допустим только для NONE / READ_ONLY / LOCAL_DEVICE_SAFE.
     */
    fun requiredPermissionsFor(action: ProposedAction): Set<String> {
        val tool = action.toolId?.lowercase().orEmpty()
        return when (action.kind) {
            ActionKind.NONE, ActionKind.READ_ONLY, ActionKind.LOCAL_DEVICE_SAFE -> emptySet()
            ActionKind.MEDIA_CONTROL -> setOf("capability:MEDIA_PLAYBACK")
            ActionKind.LAUNCH_APP -> setOf("capability:APP_LAUNCH")
            ActionKind.SYSTEM_SETTINGS -> setOf("android.permission.WRITE_SETTINGS")
            ActionKind.COMMUNICATION -> {
                if (tool == "sms" || action.actionId.contains("sms")) {
                    setOf("android.permission.SEND_SMS")
                } else {
                    setOf("android.permission.CALL_PHONE")
                }
            }
            ActionKind.UI_AUTOMATION -> setOf("capability:ACCESSIBILITY")
            ActionKind.DATA_DELETION -> setOf("capability:DATA_DELETE")
            ActionKind.FINANCIAL -> setOf("capability:FINANCIAL")
            ActionKind.UNKNOWN -> setOf("capability:UNKNOWN_EXPLICIT")
        }
    }

    fun validateArguments(
        action: ProposedAction,
        allowedKeys: Set<String>,
        forbiddenKeys: Set<String>,
    ): ActionSafetyFailureReason? {
        for (key in action.arguments.keys) {
            val lk = key.lowercase()
            if (lk in forbiddenKeys || GLOBAL_FORBIDDEN_KEYS.any { lk.contains(it) }) {
                return ActionSafetyFailureReason.FORBIDDEN_PARAMETER
            }
            if (key !in allowedKeys && lk !in allowedKeys.map { it.lowercase() }.toSet()) {
                return ActionSafetyFailureReason.UNKNOWN_PARAMETER
            }
        }
        // Kind / tool specific value checks
        val tool = action.toolId?.lowercase().orEmpty()
        val id = action.actionId.lowercase()
        if (tool == "flashlight" || id == "flashlight" || id == "torch") {
            val a = action.arguments["action"]
            if (a == null) return ActionSafetyFailureReason.INVALID_ARGUMENTS
            if (a !in setOf("on", "off", "toggle")) {
                return ActionSafetyFailureReason.INVALID_ARGUMENTS
            }
        }
        if (tool == "volume" || id.startsWith("volume")) {
            val a = action.arguments["action"]
            if (a != null && a !in setOf("up", "down", "set", "mute", "unmute")) {
                return ActionSafetyFailureReason.INVALID_ARGUMENTS
            }
            action.arguments["level"]?.let { level ->
                val n = level.toIntOrNull() ?: return ActionSafetyFailureReason.INVALID_ARGUMENTS
                if (n !in 0..100) return ActionSafetyFailureReason.INVALID_ARGUMENTS
            }
        }
        if (tool == "brightness" || id.startsWith("brightness")) {
            action.arguments["level"]?.let { level ->
                val n = level.toIntOrNull() ?: return ActionSafetyFailureReason.INVALID_ARGUMENTS
                if (n !in 0..255) return ActionSafetyFailureReason.INVALID_ARGUMENTS
            }
        }
        return null
    }
}
