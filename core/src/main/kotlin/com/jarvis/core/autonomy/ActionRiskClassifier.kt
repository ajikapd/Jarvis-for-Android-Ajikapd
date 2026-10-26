package com.jarvis.core.autonomy

/**
 * Stage 8B: deterministic local risk classifier.
 *
 * `ProposedAction → ActionRiskAssessment`
 *
 * Без LLM / ML / network / ToolExecutor / Android execution.
 * Не мутирует [ProposedAction]. Не решает ALLOW/DENY.
 */
class ActionRiskClassifier {

    fun classify(action: ProposedAction): ActionRiskAssessment {
        val kind = resolveKind(action)
        val (risk, reasonCode, reason, factors) = assess(kind, action.actionId, action.toolId)
        return ActionRiskAssessment(
            riskClass = risk,
            reasonCode = reasonCode,
            reason = reason,
            factors = factors,
            actionId = action.actionId,
            kind = kind,
        )
    }

    /**
     * Typed [ActionKind] предпочтителен. Если [ActionKind.UNKNOWN] —
     * пробуем известные actionId/toolId; иначе CRITICAL.
     */
    private fun resolveKind(action: ProposedAction): ActionKind {
        if (action.kind != ActionKind.UNKNOWN) return action.kind
        return kindFromIds(action.actionId, action.toolId) ?: ActionKind.UNKNOWN
    }

    private fun kindFromIds(actionId: String, toolId: String?): ActionKind? {
        val id = actionId.lowercase()
        val tool = toolId?.lowercase().orEmpty()
        val token = "$id|$tool"
        return when {
            id in NONE_IDS || tool in NONE_IDS -> ActionKind.NONE
            id.startsWith("read_") || id in READ_IDS || tool in READ_TOOLS -> ActionKind.READ_ONLY
            id in FLASHLIGHT_IDS || tool == "flashlight" -> ActionKind.LOCAL_DEVICE_SAFE
            id in VOLUME_BRIGHTNESS_IDS || tool in setOf("volume", "brightness") ->
                ActionKind.LOCAL_DEVICE_SAFE
            id in LAUNCH_IDS || tool == "launcher" -> ActionKind.LAUNCH_APP
            id in MEDIA_IDS || tool == "music" -> ActionKind.MEDIA_CONTROL
            id in COMM_IDS || tool in COMM_TOOLS -> ActionKind.COMMUNICATION
            id in SETTINGS_IDS || tool in setOf("settings", "system_setting", "system.setting") ->
                ActionKind.SYSTEM_SETTINGS
            id in UI_IDS || tool.startsWith("ui.") -> ActionKind.UI_AUTOMATION
            id in DELETE_IDS || token.contains("delete") || token.contains("clear_memory") ->
                ActionKind.DATA_DELETION
            id in FINANCIAL_IDS || token.contains("pay") || token.contains("purchase") ->
                ActionKind.FINANCIAL
            else -> null
        }
    }

    private data class Assessed(
        val risk: ActionRiskClass,
        val reasonCode: ActionRiskReasonCode,
        val reason: String,
        val factors: List<String>,
    )

    private fun assess(kind: ActionKind, actionId: String, toolId: String?): Assessed =
        when (kind) {
            ActionKind.NONE -> Assessed(
                ActionRiskClass.NONE,
                ActionRiskReasonCode.NO_SIDE_EFFECT,
                "No state change",
                listOf("kind=NONE", "id=$actionId"),
            )
            ActionKind.READ_ONLY -> Assessed(
                ActionRiskClass.NONE,
                ActionRiskReasonCode.READ_ONLY,
                "Read-only / no device mutation",
                listOf("kind=READ_ONLY", "id=$actionId"),
            )
            ActionKind.LOCAL_DEVICE_SAFE -> Assessed(
                ActionRiskClass.LOW,
                ActionRiskReasonCode.LOCAL_REVERSIBLE,
                "Local reversible device effect",
                listOf("kind=LOCAL_DEVICE_SAFE", "id=$actionId", "tool=${toolId ?: "-"}"),
            )
            ActionKind.LAUNCH_APP -> Assessed(
                ActionRiskClass.MEDIUM,
                ActionRiskReasonCode.USER_VISIBLE_EFFECT,
                "Launches an application (user-visible)",
                listOf("kind=LAUNCH_APP", "id=$actionId"),
            )
            ActionKind.MEDIA_CONTROL -> Assessed(
                ActionRiskClass.MEDIUM,
                ActionRiskReasonCode.USER_VISIBLE_EFFECT,
                "Media / playback control",
                listOf("kind=MEDIA_CONTROL", "id=$actionId"),
            )
            ActionKind.SYSTEM_SETTINGS -> Assessed(
                ActionRiskClass.MEDIUM,
                ActionRiskReasonCode.DIRECT_STATE_CHANGE,
                "Noticeable system state change",
                listOf("kind=SYSTEM_SETTINGS", "id=$actionId"),
            )
            ActionKind.UI_AUTOMATION -> Assessed(
                ActionRiskClass.HIGH,
                ActionRiskReasonCode.DIRECT_STATE_CHANGE,
                "UI / accessibility automation can drive arbitrary UI",
                listOf("kind=UI_AUTOMATION", "id=$actionId"),
            )
            ActionKind.COMMUNICATION -> Assessed(
                ActionRiskClass.HIGH,
                ActionRiskReasonCode.EXTERNAL_SIDE_EFFECT,
                "External communication side effect",
                listOf("kind=COMMUNICATION", "id=$actionId"),
            )
            ActionKind.DATA_DELETION -> Assessed(
                ActionRiskClass.HIGH,
                ActionRiskReasonCode.DATA_DELETION,
                "Data deletion / destructive local mutation",
                listOf("kind=DATA_DELETION", "id=$actionId"),
            )
            ActionKind.FINANCIAL -> Assessed(
                ActionRiskClass.CRITICAL,
                ActionRiskReasonCode.FINANCIAL_EFFECT,
                "Financial or purchase-related action",
                listOf("kind=FINANCIAL", "id=$actionId"),
            )
            ActionKind.UNKNOWN -> Assessed(
                // Conservative: unknown ≠ LOW/NONE. Most severe existing class.
                ActionRiskClass.CRITICAL,
                ActionRiskReasonCode.UNKNOWN_ACTION,
                "Unknown action — classified CRITICAL (conservative; not permission)",
                listOf("kind=UNKNOWN", "id=$actionId", "tool=${toolId ?: "-"}"),
            )
        }

    companion object {
        private val NONE_IDS = setOf("no_action", "none", "noop", "silence")
        private val READ_IDS = setOf("answer", "tell", "weather_query", "knowledge_query")
        private val READ_TOOLS = setOf("weather", "knowledge", "contacts.search", "files.search")
        private val FLASHLIGHT_IDS = setOf("flashlight", "torch")
        private val VOLUME_BRIGHTNESS_IDS = setOf(
            "volume_up", "volume_down", "volume_set",
            "brightness_up", "brightness_down", "brightness_set",
        )
        private val LAUNCH_IDS = setOf("launch_app", "launch_music", "open_app", "launcher")
        private val MEDIA_IDS = setOf("music_play", "music_pause", "music_next", "media_play")
        private val COMM_IDS = setOf("sms_send", "send_message", "telegram_send", "call_dial")
        private val COMM_TOOLS = setOf("sms", "telegram.send", "call", "phone.call")
        private val SETTINGS_IDS = setOf("system_setting", "toggle_setting", "wifi_toggle")
        private val UI_IDS = setOf("ui_click", "ui_type", "ui_swipe", "ui_global")
        private val DELETE_IDS = setOf("delete_data", "clear_memory", "wipe_cache", "delete_file")
        private val FINANCIAL_IDS = setOf("payment", "purchase", "pay", "checkout")
    }
}
