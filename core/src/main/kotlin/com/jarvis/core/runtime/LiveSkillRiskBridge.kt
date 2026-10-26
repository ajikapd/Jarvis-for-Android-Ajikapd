package com.jarvis.core.runtime

import com.jarvis.core.autonomy.ActionKind
import com.jarvis.core.autonomy.ProposedAction
import com.jarvis.core.autonomy.ProposedActionMapper
import com.jarvis.core.common.PolicyDecision
import com.jarvis.core.common.RiskBasedPolicy
import com.jarvis.core.common.ToolPolicy
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition

/**
 * V2-01: адаптер legacy [RiskBasedPolicy] → [ProposedAction] для единого
 * AutonomyActionPipeline.
 *
 * Сохраняет UX-семантику RiskLevel (SAFE/LOW allow, CONFIRM/SENSITIVE confirm,
 * BLOCKED deny), маппя её в ActionKind / allowlist, понятные 8A–8F.
 */
class LiveSkillRiskBridge(
    private val legacyPolicy: ToolPolicy = RiskBasedPolicy(),
) {

    data class BridgedSkill(
        val legacy: PolicyDecision,
        val proposed: ProposedAction,
        /** Action ids to allowlist so HIGH→CONFIRM (SENSITIVE) instead of silent DENY. */
        val allowlist: Set<String> = emptySet(),
    )

    fun bridge(toolCall: ToolCall, definition: ToolDefinition): BridgedSkill {
        val legacy = legacyPolicy.decide(definition)
        val args = stringifyArgs(toolCall.arguments)
        val mapped = ProposedActionMapper.fromToolId(
            toolId = toolCall.toolId,
            actionId = toolCall.toolId,
            args = args,
            displaySummary = definition.name,
        )
        val kind = kindForRiskLevel(definition.riskLevel, toolCall.toolId, mapped?.kind)
        val proposed = ProposedAction(
            actionId = toolCall.toolId,
            kind = kind,
            displaySummary = definition.name.take(ProposedAction.MAX_SUMMARY),
            toolId = toolCall.toolId,
            arguments = args,
        )
        val allowlist = when (definition.riskLevel) {
            RiskLevel.SENSITIVE, RiskLevel.CONFIRM -> setOf(proposed.actionId)
            else -> emptySet()
        }
        return BridgedSkill(legacy = legacy, proposed = proposed, allowlist = allowlist)
    }

    /**
     * RiskLevel → ActionKind так, чтобы Autonomy SAFE_ONLY совпадал с RiskBasedPolicy UX.
     */
    fun kindForRiskLevel(
        risk: RiskLevel,
        toolId: String,
        mappedKind: ActionKind?,
    ): ActionKind = when (risk) {
        RiskLevel.SAFE, RiskLevel.LOW -> {
            when {
                toolId in READ_TOOLS || mappedKind == ActionKind.READ_ONLY ->
                    ActionKind.READ_ONLY
                else -> ActionKind.LOCAL_DEVICE_SAFE
            }
        }
        // MEDIUM under SAFE_ONLY → REQUIRE_CONFIRMATION
        RiskLevel.CONFIRM -> ActionKind.MEDIA_CONTROL
        // HIGH + allowlist → REQUIRE_CONFIRMATION (never silent ALLOW)
        RiskLevel.SENSITIVE -> ActionKind.COMMUNICATION
        RiskLevel.BLOCKED -> ActionKind.UNKNOWN
    }

    companion object {
        val READ_TOOL_IDS: Set<String> = setOf(
            "weather", "knowledge", "clipboard", "notifications.list",
            "contacts.search", "files.search", "calendar",
        )

        private val READ_TOOLS = READ_TOOL_IDS

        fun stringifyArgs(raw: Map<String, Any>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in raw) {
                if (out.size >= ProposedAction.MAX_ARGS) break
                if (k.length > ProposedAction.MAX_KEY) continue
                out[k] = v.toString().take(ProposedAction.MAX_VALUE)
            }
            return out
        }
    }
}
