package com.jarvis.core.skills

import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel

/**
 * Манифест скилла (навыка). Скилл — это данные (манифест), а не код, вплетённый
 * в логику распознавания. Манифесты описывают для LLM/Brain-а, что делает скилл,
 * какие параметры принимает и в каких ситуациях применять (JARVIS_BRAIN_ARCHITECTURE §3).
 */
data class SkillManifest(
    val id: String,
    val name: String,
    val description: String,
    val examples: List<String> = emptyList(),
    val parameters: List<ManifestParameter> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.SAFE,
    val onlineRequired: Boolean = false
) {

    /** Текст для эмбеддинга/сравнения: описание + примеры. */
    val searchableText: String
        get() = (listOf(description) + examples).joinToString(" ")

    data class ManifestParameter(
        val name: String,
        val description: String,
        val type: ParameterType = ParameterType.STRING,
        val required: Boolean = false,
        val allowedValues: List<String> = emptyList()
    )
}