package com.jarvis.core.common

import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolDefinition

/**
 * Политика безопасности: решает, разрешено ли выполнить инструмент.
 * LLM/Intent никогда не выполняют действия напрямую — только через этот слой.
 */
interface ToolPolicy {
    fun decide(definition: ToolDefinition): PolicyDecision
}

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class RequiresConfirmation(val reason: String) : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

/**
 * Минимальная политика по уровню риска.
 * Расширяется с появлением permissions/конфигурации пользователя.
 */
class RiskBasedPolicy : ToolPolicy {

    override fun decide(definition: ToolDefinition): PolicyDecision = when (definition.riskLevel) {
        RiskLevel.SAFE, RiskLevel.LOW -> PolicyDecision.Allow
        RiskLevel.CONFIRM, RiskLevel.SENSITIVE ->
            PolicyDecision.RequiresConfirmation("Действие «${definition.name}» требует подтверждения")
        RiskLevel.BLOCKED ->
            PolicyDecision.Deny("Политика запрещает действие «${definition.name}»")
    }
}