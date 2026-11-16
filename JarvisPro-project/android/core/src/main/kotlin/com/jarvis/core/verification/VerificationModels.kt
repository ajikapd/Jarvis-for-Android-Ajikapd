package com.jarvis.core.verification

/**
 * Stage 13: итог проверки tool result + observed state.
 */
enum class VerificationStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE,
    UNKNOWN,
    RETRYABLE,
    IRREVERSIBLE,
}

/**
 * Фактическое состояние устройства / среды после шага.
 * Не PhoneContext целиком — только релевантные facts для проверки.
 */
data class ObservedState(
    val capturedAtMillis: Long = 0L,
    /** Например flashlight=on, volume_level=40, screen=on. */
    val facts: Map<String, String> = emptyMap(),
    val source: String = "observer",
) {
    init {
        require(facts.size <= MAX_FACTS) { "too many facts" }
        facts.forEach { (k, v) ->
            require(k.length <= MAX_KEY) { "fact key too long" }
            require(v.length <= MAX_VALUE) { "fact value too long" }
        }
        require(source.length <= 64)
    }

    fun get(key: String): String? = facts[key]

    companion object {
        const val MAX_FACTS = 32
        const val MAX_KEY = 64
        const val MAX_VALUE = 256
    }
}

data class ExpectedEffect(
    val key: String,
    val expectedValue: String,
    val required: Boolean = true,
) {
    init {
        require(key.isNotBlank() && key.length <= ObservedState.MAX_KEY)
        require(expectedValue.length <= ObservedState.MAX_VALUE)
    }
}

/**
 * Вход Stage 13. Не исполняет tools.
 */
data class VerificationRequest(
    val stepId: String,
    val toolId: String? = null,
    /** ToolResultStatus.name или произвольный статус шага. */
    val toolResultStatus: String? = null,
    val toolResultMessage: String? = null,
    val toolResultData: Map<String, String> = emptyMap(),
    val expectedEffects: List<ExpectedEffect> = emptyList(),
    val observed: ObservedState? = null,
    /** Если true и failure → IRREVERSIBLE. */
    val irreversibleAction: Boolean = false,
) {
    init {
        require(stepId.isNotBlank())
        require(expectedEffects.size <= 16)
        require(toolResultData.size <= 16)
    }
}

data class VerificationResult(
    val status: VerificationStatus,
    val matched: List<String> = emptyList(),
    val mismatched: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val ok: Boolean
        get() = status == VerificationStatus.SUCCESS ||
            status == VerificationStatus.PARTIAL_SUCCESS
}

/**
 * Expected effects для известных native tools (Stage 13 helpers).
 */
object ExpectedEffects {
    fun forTool(toolId: String, args: Map<String, String> = emptyMap()): List<ExpectedEffect> =
        when (toolId) {
            "flashlight" -> {
                val action = args["action"] ?: "on"
                val value = when (action) {
                    "off" -> "off"
                    "toggle" -> "unknown"
                    else -> "on"
                }
                if (value == "unknown") {
                    emptyList()
                } else {
                    listOf(ExpectedEffect("flashlight", value))
                }
            }
            "volume" -> {
                val action = args["action"] ?: "up"
                listOf(
                    ExpectedEffect(
                        key = "volume_action_applied",
                        expectedValue = action,
                        required = false,
                    ),
                )
            }
            "brightness" -> listOf(
                ExpectedEffect(
                    key = "brightness_changed",
                    expectedValue = "true",
                    required = false,
                ),
            )
            else -> emptyList()
        }
}
