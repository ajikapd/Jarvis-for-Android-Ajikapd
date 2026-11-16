package com.jarvis.core.capability

import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus

/**
 * Execution contract: CapabilityCall → ToolCall → ToolExecutor → verify.
 * LLM не вызывает Android напрямую.
 */
class CapabilityExecutor(
    private val registry: CapabilityRegistry,
    private val toolExecutor: ToolExecutor,
    private val verifier: CapabilityVerifier = DefaultCapabilityVerifier(),
) {

    fun execute(call: CapabilityCall): CapabilityResult {
        val cap = registry.find(call.capabilityId)
            ?: return CapabilityResult(
                requestId = call.requestId,
                capabilityId = call.capabilityId,
                status = ToolResultStatus.FAILED,
                message = "Capability не найдена: ${call.capabilityId}",
            )

        val args = buildMap {
            putAll(call.arguments)
            call.operation?.takeIf { it.isNotBlank() }?.let { op ->
                if (!containsKey("action") && !containsKey("operation")) {
                    put("action", op)
                }
            }
        }

        val toolCall = ToolCall(
            requestId = call.requestId,
            toolId = cap.backingToolId,
            arguments = args,
        )
        val toolResult = toolExecutor.execute(toolCall)
        val verification = verifier.verify(cap, call, toolResult)

        return CapabilityResult(
            requestId = call.requestId,
            capabilityId = cap.id,
            status = toolResult.status,
            message = toolResult.message,
            data = toolResult.data + mapOf(
                "capabilityId" to cap.id,
                "backingToolId" to cap.backingToolId,
            ),
            durationMs = toolResult.durationMs,
            verified = verification.ok,
            verificationNotes = verification.notes,
            toolResult = toolResult,
        )
    }

    /** Удобный мост: toolId → capability, если зарегистрирован. */
    fun executeViaToolId(
        requestId: String,
        toolId: String,
        arguments: Map<String, Any>,
    ): CapabilityResult? {
        val cap = registry.findByToolId(toolId) ?: return null
        return execute(
            CapabilityCall(
                requestId = requestId,
                capabilityId = cap.id,
                arguments = arguments,
            ),
        )
    }
}

fun interface CapabilityVerifier {
    fun verify(
        capability: CapabilityDescriptor,
        call: CapabilityCall,
        toolResult: com.jarvis.core.common.model.ToolResult,
    ): VerificationOutcome
}

data class VerificationOutcome(
    val ok: Boolean,
    val notes: List<String> = emptyList(),
)

/**
 * Базовая проверка: SUCCESS ⇒ verified; иначе notes с причиной.
 * Не имитирует успех по тексту LLM.
 */
class DefaultCapabilityVerifier : CapabilityVerifier {
    override fun verify(
        capability: CapabilityDescriptor,
        call: CapabilityCall,
        toolResult: com.jarvis.core.common.model.ToolResult,
    ): VerificationOutcome {
        return when (toolResult.status) {
            ToolResultStatus.SUCCESS -> VerificationOutcome(
                ok = true,
                notes = listOf("tool_success", "capability=${capability.id}"),
            )
            ToolResultStatus.NEEDS_CONFIRMATION -> VerificationOutcome(
                ok = false,
                notes = listOf("needs_confirmation"),
            )
            ToolResultStatus.DENIED -> VerificationOutcome(
                ok = false,
                notes = listOf("denied"),
            )
            ToolResultStatus.OFFLINE -> VerificationOutcome(
                ok = false,
                notes = listOf("offline", "onlineRequirement=${capability.onlineRequirement}"),
            )
            ToolResultStatus.UNAVAILABLE -> VerificationOutcome(
                ok = false,
                notes = listOf("unavailable", "availability=${capability.availability}"),
            )
            ToolResultStatus.AMBIGUOUS -> VerificationOutcome(
                ok = false,
                notes = listOf("ambiguous"),
            )
            ToolResultStatus.FAILED -> VerificationOutcome(
                ok = false,
                notes = listOf("failed", toolResult.message.orEmpty().take(80)),
            )
        }
    }
}
