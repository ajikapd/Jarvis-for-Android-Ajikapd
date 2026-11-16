package com.jarvis.core.aioutput

/**
 * V2-05: schema validation (types/required/enums/unknown fields) — not tool existence.
 */
object StructuredAiSchemaValidator {

    fun validate(json: JsonVal.Obj): SchemaValidationResult {
        val unknown = StructuredAiOutputParser.unknownTopLevelKeys(json)
        if (unknown.isNotEmpty()) {
            return SchemaValidationResult.Fail("unknown_fields:${unknown.joinToString(",")}")
        }

        val version = stringOf(json, "schemaVersion")?.trim().orEmpty()
        if (version.isBlank()) return SchemaValidationResult.Fail("missing_schemaVersion")
        if (version != StructuredAiLimits.SCHEMA_VERSION) {
            return SchemaValidationResult.Fail("unsupported_schema:$version")
        }

        val typeRaw = stringOf(json, "type")?.trim()?.uppercase().orEmpty()
        if (typeRaw.isBlank()) return SchemaValidationResult.Fail("missing_type")
        val type = runCatching { StructuredAiResponseType.valueOf(typeRaw) }.getOrNull()
            ?: return SchemaValidationResult.Fail("invalid_type:$typeRaw")

        val confidence = when (val c = json.opt("confidence")) {
            null, JsonVal.Null -> null
            is JsonVal.Num -> {
                if (c.value < 0.0 || c.value > 1.0) {
                    return SchemaValidationResult.Fail("invalid_confidence")
                }
                c.value.toFloat()
            }
            else -> return SchemaValidationResult.Fail("invalid_confidence")
        }

        val requestId = stringOf(json, "requestId")?.takeIf { it.isNotBlank() }
        val goal = boundOpt(json, "goal", StructuredAiLimits.MAX_GOAL_CHARS)
        val intent = boundOpt(json, "intent", 64)
        val suggestedRoute = boundOpt(json, "suggestedRoute", 32)?.uppercase()
        val ambiguity = boundOpt(json, "ambiguity", 16)?.uppercase()

        return when (type) {
            StructuredAiResponseType.ANSWER -> {
                val text = requiredString(json, "text", StructuredAiLimits.MAX_TEXT_CHARS)
                    ?: return SchemaValidationResult.Fail("missing_or_empty_text")
                SchemaValidationResult.Ok(
                    StructuredAiEnvelope(
                        schemaVersion = version,
                        responseType = type,
                        requestId = requestId,
                        confidence = confidence,
                        text = text,
                        goal = goal,
                        intent = intent,
                        suggestedRoute = suggestedRoute,
                        ambiguity = ambiguity,
                    ),
                )
            }
            StructuredAiResponseType.CLARIFICATION -> {
                val question = requiredString(json, "question", StructuredAiLimits.MAX_QUESTION_CHARS)
                    ?: return SchemaValidationResult.Fail("missing_or_empty_question")
                val reasonRaw = stringOf(json, "reason")?.trim()?.uppercase()?.ifBlank { null } ?: "OTHER"
                val reason = runCatching { ClarificationReason.valueOf(reasonRaw) }.getOrNull()
                    ?: return SchemaValidationResult.Fail("invalid_reason:$reasonRaw")
                SchemaValidationResult.Ok(
                    StructuredAiEnvelope(
                        schemaVersion = version,
                        responseType = type,
                        requestId = requestId,
                        confidence = confidence,
                        question = question,
                        clarificationReason = reason,
                        text = question,
                        goal = goal,
                        intent = intent,
                        suggestedRoute = suggestedRoute ?: "CLARIFY",
                        ambiguity = ambiguity,
                    ),
                )
            }
            StructuredAiResponseType.TOOL_REQUEST -> {
                val toolId = requiredString(json, "toolId", StructuredAiLimits.MAX_TOOL_ID)
                    ?: return SchemaValidationResult.Fail("missing_or_empty_toolId")
                val args = parseStringMap(json.opt("arguments"))
                    ?: return SchemaValidationResult.Fail("invalid_arguments")
                if (args.size > StructuredAiLimits.MAX_ARGS) {
                    return SchemaValidationResult.Fail("too_many_arguments")
                }
                SchemaValidationResult.Ok(
                    StructuredAiEnvelope(
                        schemaVersion = version,
                        responseType = type,
                        requestId = requestId,
                        confidence = confidence,
                        toolId = toolId,
                        arguments = args,
                        goal = goal,
                        intent = intent,
                        suggestedRoute = suggestedRoute,
                        ambiguity = ambiguity,
                        text = boundOpt(json, "text", StructuredAiLimits.MAX_TEXT_CHARS),
                    ),
                )
            }
            StructuredAiResponseType.PLAN -> {
                val planGoal = goal ?: requiredString(json, "goal", StructuredAiLimits.MAX_GOAL_CHARS)
                    ?: return SchemaValidationResult.Fail("missing_or_empty_goal")
                val stepsArr = json.opt("steps")?.asArr()
                    ?: return SchemaValidationResult.Fail("missing_steps")
                if (stepsArr.items.isEmpty()) return SchemaValidationResult.Fail("empty_steps")
                if (stepsArr.items.size > StructuredAiLimits.MAX_PLAN_STEPS) {
                    return SchemaValidationResult.Fail("too_many_steps")
                }
                val steps = mutableListOf<StructuredPlanStepDraft>()
                stepsArr.items.forEachIndexed { i, item ->
                    val s = item.asObj() ?: return SchemaValidationResult.Fail("invalid_step_$i")
                    val stepUnknown = s.keys().filter {
                        it !in setOf(
                            "stepId", "kind", "toolId", "parameters", "dependsOn",
                            "requiresConfirmation", "displaySummary", "waitMs",
                        )
                    }
                    if (stepUnknown.isNotEmpty()) {
                        return SchemaValidationResult.Fail("unknown_step_fields:${stepUnknown.joinToString(",")}")
                    }
                    val stepId = requiredString(s, "stepId", 64)
                        ?: return SchemaValidationResult.Fail("missing_stepId_$i")
                    val kind = requiredString(s, "kind", 16)
                        ?: return SchemaValidationResult.Fail("missing_kind_$i")
                    val params = parseStringMap(s.opt("parameters")) ?: emptyMap()
                    if (params.size > StructuredAiLimits.MAX_ARGS) {
                        return SchemaValidationResult.Fail("too_many_step_params_$i")
                    }
                    val deps = parseStringList(s.opt("dependsOn"))
                        ?: return SchemaValidationResult.Fail("invalid_dependsOn_$i")
                    val requiresConfirmation = when (val b = s.opt("requiresConfirmation")) {
                        is JsonVal.Bool -> b.value
                        else -> false
                    }
                    val waitMs = when (val w = s.opt("waitMs")) {
                        is JsonVal.Num -> w.value.toLong().coerceIn(0L, 3_600_000L)
                        else -> 0L
                    }
                    steps += StructuredPlanStepDraft(
                        stepId = stepId,
                        kind = kind.uppercase(),
                        toolId = boundOpt(s, "toolId", StructuredAiLimits.MAX_TOOL_ID),
                        parameters = params,
                        dependsOn = deps,
                        requiresConfirmation = requiresConfirmation,
                        displaySummary = boundOpt(s, "displaySummary", 160).orEmpty(),
                        waitMs = waitMs,
                    )
                }
                SchemaValidationResult.Ok(
                    StructuredAiEnvelope(
                        schemaVersion = version,
                        responseType = type,
                        requestId = requestId,
                        confidence = confidence,
                        goal = planGoal,
                        intent = intent,
                        suggestedRoute = suggestedRoute ?: "PLAN",
                        ambiguity = ambiguity,
                        planSteps = steps,
                        text = boundOpt(json, "text", StructuredAiLimits.MAX_TEXT_CHARS),
                    ),
                )
            }
            StructuredAiResponseType.NO_ACTION -> {
                if (!stringOf(json, "toolId").isNullOrBlank()) {
                    return SchemaValidationResult.Fail("no_action_with_tool")
                }
                if (json.has("steps")) {
                    return SchemaValidationResult.Fail("no_action_with_steps")
                }
                SchemaValidationResult.Ok(
                    StructuredAiEnvelope(
                        schemaVersion = version,
                        responseType = type,
                        requestId = requestId,
                        confidence = confidence,
                        noActionReason = boundOpt(json, "noActionReason", 160),
                        goal = goal,
                        intent = intent,
                        suggestedRoute = suggestedRoute,
                        ambiguity = ambiguity,
                    ),
                )
            }
            StructuredAiResponseType.CONFIRMATION_REQUIRED -> {
                val summary = requiredString(json, "summary", 160)
                    ?: boundOpt(json, "text", 160)
                    ?: return SchemaValidationResult.Fail("missing_summary")
                val toolId = boundOpt(json, "toolId", StructuredAiLimits.MAX_TOOL_ID)
                val args = parseStringMap(json.opt("arguments")) ?: emptyMap()
                SchemaValidationResult.Ok(
                    StructuredAiEnvelope(
                        schemaVersion = version,
                        responseType = type,
                        requestId = requestId,
                        confidence = confidence,
                        confirmationSummary = summary,
                        text = summary,
                        toolId = toolId,
                        arguments = args,
                        goal = goal,
                        intent = intent,
                        suggestedRoute = suggestedRoute,
                        ambiguity = ambiguity,
                    ),
                )
            }
        }
    }

    private fun stringOf(obj: JsonVal.Obj, key: String): String? =
        obj.opt(key)?.asString()

    private fun requiredString(obj: JsonVal.Obj, key: String, max: Int): String? {
        val v = stringOf(obj, key)?.trim().orEmpty()
        if (v.isBlank()) return null
        return v.take(max)
    }

    private fun boundOpt(obj: JsonVal.Obj, key: String, max: Int): String? {
        val v = stringOf(obj, key)?.trim().orEmpty()
        return v.takeIf { it.isNotBlank() }?.take(max)
    }

    private fun parseStringMap(raw: JsonVal?): Map<String, String>? {
        if (raw == null || raw is JsonVal.Null) return emptyMap()
        val obj = raw.asObj() ?: return null
        val out = linkedMapOf<String, String>()
        for ((k, v) in obj.map) {
            if (k.length > StructuredAiLimits.MAX_ARG_KEY) return null
            val s = v.asString() ?: return null
            if (s.length > StructuredAiLimits.MAX_ARG_VALUE) return null
            out[k] = s
        }
        return out
    }

    private fun parseStringList(raw: JsonVal?): List<String>? {
        if (raw == null || raw is JsonVal.Null) return emptyList()
        val arr = raw.asArr() ?: return null
        val out = mutableListOf<String>()
        for (item in arr.items) {
            val s = (item as? JsonVal.Str)?.value ?: return null
            out += s.take(64)
        }
        return out
    }
}

sealed class SchemaValidationResult {
    data class Ok(val envelope: StructuredAiEnvelope) : SchemaValidationResult()
    data class Fail(val reason: String) : SchemaValidationResult()
}
