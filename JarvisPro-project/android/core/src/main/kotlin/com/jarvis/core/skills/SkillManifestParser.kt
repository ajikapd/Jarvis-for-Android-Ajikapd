package com.jarvis.core.skills

import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import org.json.JSONObject

/**
 * Читает манифест скилла из JSON (формат JARVIS_BRAIN_ARCHITECTURE §3).
 * Пример:
 * {
 *   "id": "flashlight.toggle",
 *   "name": "Фонарик",
 *   "description": "Включает, выключает или переключает фонарик камеры устройства. ...",
 *   "examples": ["включи фонарик", "тут темно", "посвети"],
 *   "parameters": [
 *     { "name": "state", "description": "on|off|toggle", "type": "enum",
 *       "required": true, "allowedValues": ["on", "off", "toggle"] }
 *   ],
 *   "riskLevel": "low",
 *   "onlineRequired": false
 * }
 */
object SkillManifestParser {

    fun parse(json: String): SkillManifest {
        val root = JSONObject(json)
        val params = root.optJSONArray("parameters")
        val parameters = buildList {
            if (params != null) {
                for (i in 0 until params.length()) {
                    val p = params.getJSONObject(i)
                    add(
                        SkillManifest.ManifestParameter(
                            name = p.getString("name"),
                            description = p.optString("description", ""),
                            type = parseType(p.optString("type", "string")),
                            required = p.optBoolean("required", false),
                            allowedValues = p.optJSONArray("allowedValues")
                                ?.let { arr -> buildList { for (j in 0 until arr.length()) add(arr.getString(j)) } }
                                ?: emptyList()
                        )
                    )
                }
            }
        }
        return SkillManifest(
            id = root.getString("id"),
            name = root.getString("name"),
            description = root.getString("description"),
            examples = root.optJSONArray("examples")
                ?.let { arr -> buildList { for (i in 0 until arr.length()) add(arr.getString(i)) } }
                ?: emptyList(),
            parameters = parameters,
            riskLevel = parseRisk(root.optString("riskLevel", "low")),
            onlineRequired = root.optBoolean("onlineRequired", false)
        )
    }

    private fun parseType(type: String): ParameterType =
        when (type.lowercase()) {
            "integer", "int" -> ParameterType.INTEGER
            "boolean", "bool" -> ParameterType.BOOLEAN
            "enum" -> ParameterType.ENUM
            else -> ParameterType.STRING
        }

    private fun parseRisk(risk: String): RiskLevel =
        when (risk.lowercase()) {
            "sensitive" -> RiskLevel.SENSITIVE
            "confirm" -> RiskLevel.CONFIRM
            "blocked" -> RiskLevel.BLOCKED
            "medium" -> RiskLevel.LOW
            else -> RiskLevel.SAFE
        }
}